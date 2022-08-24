

from contextlib import closing
import os
import platform
import socket
import subprocess
from subprocess import PIPE
import tempfile

from py4j.java_gateway import JavaGateway, GatewayParameters
from py4j.java_collections import ListConverter

from colomoto import minibn
from colomoto_jupyter.sessionfiles import new_output_file

__ERODE_LIB_DIR__ = os.path.join(os.path.dirname(__file__), "lib")
__ERODE_JAR__ = os.path.join(os.path.dirname(__file__), "erode4Colomoto.jar")

def _start_server():
    # find a free port
    for port in range(25333, 65545):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
            dest_addr = ("127.0.0.1", port)
            if s.connect_ex(dest_addr):
                break

    ld_path = __ERODE_LIB_DIR__
    argv = ["java", f'-Djava.library.path="{ld_path}"',
                "-jar", __ERODE_JAR__, str(port)]

    if platform.system() == "Linux":
        env_ld_path = os.getenv("LD_LIBRARY_PATH")
        if env_ld_path:
            ld_path = f"{ld_path}:{env_ld_path}"
        env ={"LD_LIBRARY_PATH": ld_path}
        proc = subprocess.Popen(" ".join(argv), stdout=PIPE,
                shell=True, env=env)
    else:
        proc = subprocess.Popen(argv, stdout=PIPE)
    proc.stdout.readline()
    return proc, port

def _stop_server(proc):
    proc.terminate()
    try:
        proc.wait(5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()

class ErodePartition(dict):
    """
    Dict-like representation of an ERODE parition.
    Maps each species to a block identifier.
    """
    def __init__(self, instance, values):
        self._instance = instance
        super().__init__(zip(self._instance.species, values))

    def __setitem__(self, k, v):
        if k not in self._instance.species:
            raise KeyError(k)
        super().__setitem__(k,v)

    def for_erode(self):
        """
        Converts to a Java Array for ERODE Java API
        """
        a = [self[k] for k in self._instance.species]
        return self._instance.make_jarray(a)

    def __str__(self):
        return self._instance.japi.getPartitionString(self.for_erode())


class ErodeInstance(object):
    """
    Interface to ERODE.
    The internal Java API is available with the `japi` property.
    """
    def __init__(self, model, usermode="INPUTS"):
        """
        Creates an ERODE instance for computing model reduction.

        :param model: Boolean network model
        :type model: filename in BoolNet format (`".bnet"`) or object of type
            :py:class:`colomoto.minibn.BooleanNetwork` or any type accepted by
            :py:class:`colomoto.minibn.BooleanNetwork` constructor
        :param str usermode: mode for user partition, among
            - `"INPUTS"`: every input  variable is isolated in a singleton block (plus one block for the other variables)
            - `"OUTPUTS"`: every output variable is isolated in a singleton block (plus one block for the other variables)
            - `"INPUTSONEBLOCK"`:all input  variables are in the same block (plus one block for the other variables)
            - `"OUTPUTSONEBLOCK"`: all output variables are in the same block (plus one block for the other variables)
        """
        self._proc, self._port = _start_server()
        gw_params = GatewayParameters(port=self._port)#, auto_convert=True)
        self._gw = JavaGateway(gateway_parameters=gw_params)
        self.japi = self._gw.entry_point

        self.load_model(model, usermode)

    def make_jarray(self, seq):
        """
        Returns a java Array[int] of the given `seq` collection.
        """
        jarray = self._gw.new_array(self._gw.jvm.int, len(seq))
        for i, v in enumerate(seq):
            jarray[i] = v
        return jarray

    def __del__(self):
        try:
            self._gw.shutdown()
        except:
            pass
        _stop_server(self._proc)

    def load_model(self, model, usermode):
        """
        See `__init__` method
        """
        if isinstance(model, str):
            assert os.path.exists(model)
            filename = os.path.abspath(model)
        else:
            bn = minibn.BooleanNetwork(model)
            filename = new_output_file("bnet")
            with open(filename, "w") as f:
                f.write(bn.source())
        self.japi.loadBNet(filename, usermode)
        self.reload()

    def reload(self):
        self.species = list(self.japi.getSpeciesNames())
        self.partition = self.get_initial_partition()

    def __str__(self):
        return self.japi.getModelString()

    def get_user_partition(self):
        return ErodePartition(self, list(self.japi.getUserPartition()))

    def get_initial_partition(self):
        return ErodePartition(self, list(self.japi.getInitialPartition()))

    def set_partition(self, partition):
        """
        Set the current partition.

        :param dict[str,int] partition: dictionnary assigning species to block id
        """
        for k, v in partition.items():
            self.partition[k] = v

    def bbe_partition(self, partition=None, apply=True):
        """
        Compute BBE partition from the given partition, or the current partition (`.partition`
        property) if none is provided.

        Returns the computed partition as a :py:class:`.ErodePartition` object.

        :param dict[str,int] partition: dictionnary assigning species to block id
        :param bool apply: if True, update the current partition
        """
        if partition is not None:
            self.set_partition(partition)
        bbe_p = self.japi.computeBBEPartition(self.partition.for_erode())
        bbe_p = ErodePartition(self, list(bbe_p))
        if apply:
            self.set_partition(bbe_p)
        return bbe_p

    def reduce_model(self, partition=None, output_bnet=None, ret="auto"):
        """
        Reduce the model according the the given parition, or the current
        parition (`.partition` property) if none is provided.

        :param dict[str,int] partition: dictionnary assigning species to block id
        :param str output_bnet: Filename for BoolNet export of the reduced model
        :param ret: if `"auto"` (default), the methods returns
            :py:class:`colomoto.minibn.BooleanNetwork` object of the reduced
            model unless `output_bnet` is specified. Otherwise the object is
            returned if `True`.
        """
        partition = partition if partition is not None else self.partition
        self.japi.computeBBEReducedModel(partition.for_erode())
        self.reload()
        for i, k in enumerate(self.species):
            self.partition[k] = i+1

        bnetfile = None
        if output_bnet:
            output_bnet = os.path.abspath(output_bnet)
            bnetfile = output_bnet
            if ret == "auto":
                ret = False
        elif ret:
            bnetfile = new_output_file("bnet")
        if bnetfile:
            self.japi.writeBNet(bnetfile)
            if ret:
                return minibn.BooleanNetwork.load(bnetfile)


def load(*args, **kwargs):
    """
    See :py:class:`.ErodeInstance`
    """
    return ErodeInstance(*args, **kwargs)

def bbe_reduction(model, output_bnet=None, usermode=None, initial_partition=None):
    """
    Computes the Backward Boolean Equivalence reduction of the given Boolean networks
    specified by `model`.

    Returns a :py:class:`colomoto.minibn.BooleanNetwork` object of the reduced
    model, unless `output_bnet` is supplied. In this case, the reduced model is
    written to the filename `output_bnet`, and nothing is returned.

    :param model: Boolean network model
    :type model: filename in BoolNet format (`".bnet"`) or object of type
        :py:class:`colomoto.minibn.BooleanNetwork` or any type accepted by
        :py:class:`colomoto.minibn.BooleanNetwork` constructor
    :param str output_bnet: Filename for BoolNet export of the reduced model
    :param str usermode: predefined initial partition
        - `"INPUTS"`: every input  variable is isolated in a singleton block (plus one block for the other variables)
        - `"OUTPUTS"`: every output variable is isolated in a singleton block (plus one block for the other variables)
        - `"INPUTSONEBLOCK"`:all input  variables are in the same block (plus one block for the other variables)
        - `"OUTPUTSONEBLOCK"`: all output variables are in the same block (plus one block for the other variables)
    :param dict[str,int] initial_partition: initial partition of the species.
       The dictionnary must map each species of the model to a block identifier.
    """
    ei_opts = {"usermode": usermode} if usermode else {}
    e = ErodeInstance(model, **ei_opts)
    if usermode:
        e.set_partition(e.get_user_partition())
    if initial_partition is not None:
        e.set_partition(initial_partition)
    e.bbe_partition()
    return e.reduce_model(output_bnet=output_bnet)
