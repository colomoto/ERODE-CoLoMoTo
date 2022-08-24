import os
import platform
import urllib.request
import zipfile

import setuptools.command.build_py
from setuptools import setup

import logging

JAR_URL = "https://github.com/colomoto/ERODE-CoLoMoTo/raw/main/it.sssa.erode.colomoto/distr/erode4Colomoto.jar"

PLATFORM_DEPS = {
    "Darwin": "https://github.com/IMTAltiStudiLucca/ERODE-Libraries/raw/master/z3SourceLibraries/mac.zip",
    "Linux": "https://github.com/IMTAltiStudiLucca/ERODE-Libraries/raw/master/z3SourceLibraries/linux64.zip",
    "Windows": "https://github.com/IMTAltiStudiLucca/ERODE-Libraries/raw/master/z3SourceLibraries/windows64.zip",
}

class build_erode_lib(setuptools.command.build_py.build_py):
    def run(self):
        target_dir = os.path.join(self.build_lib, 'erode')
        self.mkpath(target_dir)
        logging.info(f"Downloading {JAR_URL}")
        urllib.request.urlretrieve(JAR_URL,
                os.path.join(target_dir, "erode4Colomoto.jar"))

        target_dir = os.path.join(self.build_lib, 'erode/lib')
        self.mkpath(target_dir)
        dep_url = PLATFORM_DEPS[platform.system()]
        logging.info(f"Downloading {dep_url}")
        dep_zip, _= urllib.request.urlretrieve(dep_url)
        try:
            with zipfile.ZipFile(dep_zip) as z:
                for name in z.namelist():
                    bname = os.path.basename(name)
                    if bname.startswith(".") or name.startswith("_") \
                            or "." not in bname:
                        continue
                    logging.info(f"Extracting {name}")
                    with z.open(name) as src,\
                            open(os.path.join(target_dir, bname), "wb") as dst:
                        dst.write(src.read())

        finally:
            os.unlink(dep_zip)
        super().run()

setup(
    cmdclass={
        'build_py': build_erode_lib,
    }
)
