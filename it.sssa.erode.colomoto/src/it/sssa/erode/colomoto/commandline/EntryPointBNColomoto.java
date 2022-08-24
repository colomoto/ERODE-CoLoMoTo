package it.sssa.erode.colomoto.commandline;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.eclipse.ui.console.MessageConsoleStream;

import com.microsoft.z3.Z3Exception;

import it.imt.erode.auxiliarydatastructures.IPartitionAndBoolean;
import it.imt.erode.booleannetwork.bnetparser.BNetParser;
import it.imt.erode.booleannetwork.bnetparser.ParseException;
import it.imt.erode.booleannetwork.implementations.InfoBooleanNetworkReduction;
import it.imt.erode.booleannetwork.interfaces.IBooleanNetwork;
import it.imt.erode.booleannetwork.updatefunctions.BasicModelElementsCollector;
import it.imt.erode.booleannetwork.updatefunctions.IUpdateFunction;
import it.imt.erode.commandline.BooleanNetworkCommandLine;
import it.imt.erode.commandline.CRNReducerCommandLine;
import it.imt.erode.commandline.CommandsReader;
import it.imt.erode.commandline.EntryPointForPython;
//import it.imt.erode.commandline.Z3Loader;
import it.imt.erode.crn.interfaces.ISpecies;
import it.imt.erode.importing.UnsupportedFormatException;
import it.imt.erode.importing.booleannetwork.GUIBooleanNetworkImporter;
import it.imt.erode.importing.booleannetwork.GuessPrepartitionBN;
import it.imt.erode.partition.interfaces.IPartition;
import it.imt.erode.partitionrefinement.algorithms.CRNBisimulationsNAry;
import it.imt.erode.partitionrefinement.algorithms.booleannetworks.FBEAggregationFunctions;
import it.imt.erode.partitionrefinement.algorithms.booleannetworks.SMTBackwardBooleanEquivalence;
import it.imt.erode.partitionrefinement.algorithms.booleannetworks.SMTForwardBooleanEquivalence;
import py4j.GatewayServer;

public class EntryPointBNColomoto {

	private BooleanNetworkCommandLine erode;
	private MessageConsoleStream out = null;
	private BufferedWriter bwOut=null;
	private boolean printPartitions;
	private boolean printModels;
	private LinkedHashMap<String, ISpecies> nameToSpecies;
	private ISpecies[] idToSpecies;
	private String[] idToSpeciesNames;
	private boolean bnLoaded=false;
	private IPartition defaultPartition;
	
	private void checkBNLoaded() throws UnsupportedOperationException {
		if(!bnLoaded) {
			CRNReducerCommandLine.println(out,bwOut,"Please first load a model.");
			throw new UnsupportedOperationException("Please first load a model.");
		}
	}
	
	public static void main(String[] args) {
		//Z3Loader.loadZ3();
		EntryPointBNColomoto entry = new EntryPointBNColomoto(true, true);
		int port=-1;
		if(args!=null && args.length>0) {
			port = Integer.valueOf(args[0]);
		}
		GatewayServer gatewayServer = null;
		if(port==-1){
			gatewayServer=new GatewayServer(entry);
		}
		else{
			gatewayServer=new GatewayServer(entry,port);
		}
				
		gatewayServer.start();
		
		CRNReducerCommandLine.println(entry.out,entry.bwOut,"Gateway server started on port "+gatewayServer.getPort()+ " (while pythonPort is "+gatewayServer.getPythonPort()+", and pythonAddress is "+gatewayServer.getPythonAddress().toString()+")");
		
		/*
		String model="ap-1_else-0_wt.bnet";
		entry.loadBNet(model);
		
		try {
			//entry.computeBBE("MAX");
			entry.computeBBE("USER");
		} catch (Z3Exception | UnsupportedFormatException | IOException e) {
			e.printStackTrace();
		}
		*/
		
	}
	
	public EntryPointBNColomoto(boolean printPartitions, boolean printModels) {
		this.printPartitions=printPartitions;
		this.printModels=printModels;
	}
	
	public int loadBNet(String absolutePath) {
		return loadBNet(absolutePath,GuessPrepartitionBN.INPUTS.name());
	}
	
	public String getModelString() {
		checkBNLoaded();
		return erode.getBN().toString();
	}
	
	public int[] buildPartition(String guessPrepStr) {
		GuessPrepartitionBN guessPrep = handleGuessPrep(guessPrepStr);
		if(guessPrep==null) {
			return null;
		}
		
		LinkedHashMap<String, IUpdateFunction> updateFunctions = erode.getBN().getUpdateFunctions();
		BasicModelElementsCollector bMec = new BasicModelElementsCollector(guessPrep, updateFunctions);
		ArrayList<ArrayList<String>> userPrep = bMec.getUserPartition();
		ArrayList<HashSet<ISpecies>> userPrepSp = new ArrayList<>(userPrep.size());
		
		for(ArrayList<String> blockStr : userPrep) {
			HashSet<ISpecies> block = new LinkedHashSet<ISpecies>(blockStr.size());
			userPrepSp.add(block);
			for(String spName : blockStr) {
				ISpecies sp= nameToSpecies.get(spName);
				block.add(sp);
			}
		}
		IPartition user= CRNBisimulationsNAry.prepartitionUserDefined(erode.getBN().getSpecies(),userPrepSp, false, out,bwOut,null);
		int[] userPartitionArray=EntryPointForPython.exportPartition(idToSpecies, user);
		return userPartitionArray;
		
		
		/*
		LinkedHashMap<ISpecies, Integer> userPrepMap=new LinkedHashMap<>(erode.getBN().getSpecies().size());
		
		
		int blockId=1;
		for(ArrayList<String> block : userPrep) {
			for(String spName : block) {
				ISpecies sp= nameToSpecies.get(spName);
				userPrepMap.put(sp,blockId);
			}
			blockId++;
		}
		int[] userPrepArray=new int[erode.getBN().getSpecies().size()];
		for(int id=0;id<userPrepArray.length;id++) {
			ISpecies sp=erode.getBN().getSpecies().get(id);
			blockId=userPrepMap.get(sp);
			userPrepArray[id]=blockId;
		}
		
		return userPrepArray;
		*/
	}
	
	public int loadBNet(String absolutePath, String guessPrepStr) {
		GuessPrepartitionBN guessPrep = handleGuessPrep(guessPrepStr);
		if(guessPrep==null) {
			return -1;
		}
		
		String modelName = absolutePath;
		if(modelName.contains(File.separator)) {
			modelName=modelName.substring(modelName.lastIndexOf(File.separator)+1);
		}

		LinkedHashMap<String, IUpdateFunction> parsed=null;
		try {
			parsed = BNetParser.parseFile(new File(absolutePath));
		} catch (FileNotFoundException e) {
			CRNReducerCommandLine.println(out,bwOut,"File not found: "+absolutePath);
			return -1;
		} catch ( ParseException e) {
			CRNReducerCommandLine.println(out,bwOut,"Problems in parsing the model:" +e);
			return -1;
		}
		
		
		completeLoadingBN(guessPrep, modelName, parsed);
		return 1;
	}
	
	public String[] getSpeciesNames(){
		checkBNLoaded();
		return idToSpeciesNames;
	}
	
	public int[] getInitialPartition() {
		checkBNLoaded();
		int[] initial=EntryPointForPython.exportPartition(idToSpecies, erode.getPartition());
		return initial;	
	}
	public int[] getUserPartition() {
		IPartition user= CRNBisimulationsNAry.prepartitionUserDefined(erode.getBN().getSpecies(),erode.getBN().getUserDefinedPartition(), false, out,bwOut,null);
		int[] userPartitionArray=EntryPointForPython.exportPartition(idToSpecies, user);
		return userPartitionArray;
	}
	
	
	
	public void printPartition(int[] partitionArray){
		CRNReducerCommandLine.println(out,bwOut,getPartitionString(partitionArray));
	}
	public String getPartitionString(int[] partitionArray){
		checkBNLoaded();
		boolean numbersAreIDOfRepresentativeSpecies=false;
		IPartition partition = EntryPointForPython.importPartition(idToSpecies, partitionArray,numbersAreIDOfRepresentativeSpecies);
		return partition.toString();
	}
	
	
	public int[] computeBBEPartition() throws UnsupportedFormatException, Z3Exception, IOException{
		return computeBBEPartition("MAX");
	}
	public int[] computeBBEPartition(String initialPartition) throws UnsupportedFormatException, Z3Exception, IOException{
		checkBNLoaded();
		
		initialPartition=initialPartition.toUpperCase();
		if(initialPartition.equals("MAX")){
			checkBNLoaded();
			int[] initialPartitionArray = new int[idToSpecies.length];
			Arrays.fill(initialPartitionArray, 1);
			return computeBBEPartition(initialPartitionArray,false);
		}
		else if(initialPartition.equals("USER")){
			int[] initialPartitionArray = getUserPartition();
			return computeBBEPartition(initialPartitionArray,true);
		}
		else {
			CRNReducerCommandLine.println(out,bwOut,"Unexpected parameter ("+initialPartition+"). Please provide either MAX for the maximal reduction, or USER for the one computed when loading the model (e.g., inputs, outputs,...).");
			return null;
		}
	}
	public int[] computeBBEPartition(int[] initialPartitionArray) throws UnsupportedFormatException, Z3Exception, IOException{
		checkBNLoaded();
		return computeBBEPartition(initialPartitionArray,false);
	}
	public int[] computeBBEPartition(int[] initialPartitionArray, boolean numbersAreIDOfRepresentativeSpecies) throws UnsupportedFormatException, Z3Exception, IOException{
		checkBNLoaded();
		
		CRNReducerCommandLine.println(out,bwOut,"Computing BBE reduction");
		
		//CRNReducerCommandLine.println(out,bwOut,"java.library.path:"+System.getProperty("java.library.path"));

		erode.checkLibrariesZ3(out, bwOut);
		
		IPartition initialPartition = EntryPointForPython.importPartition(idToSpecies, initialPartitionArray,numbersAreIDOfRepresentativeSpecies);
		erode.setPartition(initialPartition);
		int[] obtainedPartitionToExport=null;
		try {
			if(printPartitions){
				CRNReducerCommandLine.println(out,bwOut,"Initial partition:\n"+initialPartition);
			}

			IPartitionAndBoolean obtainedPartitionAndBool = erode.handleReduceCommand("reduceBBE({computeOnlyPartition=>true,print=>false})",false,"bbe",out,bwOut);
			IPartition obtainedPartition = obtainedPartitionAndBool.getPartition(); 
			//IPartition obtainedPartition = crnreducer.handleReduceCommand("reduceEFL({computeOnlyPartition=>true,print=>false})",false,"EFL");
			if(printPartitions){
				CRNReducerCommandLine.println(out,bwOut,"Obtained partition:\n"+obtainedPartition);
			}

			obtainedPartitionToExport = EntryPointForPython.exportPartition(idToSpecies,obtainedPartition);

			CRNReducerCommandLine.println(out,bwOut,"BBE reduction completed");
		}finally {
			erode.setPartition(defaultPartition);
		}
		
		return obtainedPartitionToExport;
	}
	
	public void writeBNet(String fileOut) {
		String command="exportBoolNet({fileOut=>"+fileOut+",originalNames=>true})";
		erode.handleExportBoolNetCommand(command,out,bwOut);
	}
	
	public void computeBBEReducedModel(int[] reductionPartition) {
		boolean updateCRN=true; 
		String reduction="bbe"; 
		String reducedFileName=null; 
		//boolean print=false; 
		FBEAggregationFunctions aggr=null; 
		boolean numbersAreIDOfRepresentativeSpecies=false;
		IPartition obtainedPartition=EntryPointForPython.importPartition(idToSpecies, reductionPartition,numbersAreIDOfRepresentativeSpecies);
		String icWarning=null; 
		String reductionName=reduction.toUpperCase(); 
		String reducedModelName=erode.getBN().getName()+reductionName; 
		SMTForwardBooleanEquivalence smtFBE=null;
		boolean simplify=false;
		SMTBackwardBooleanEquivalence smtBBE= new SMTBackwardBooleanEquivalence(simplify); 
		boolean writeReducedCRN=false; 
		InfoBooleanNetworkReduction infoReduction=new InfoBooleanNetworkReduction(erode.getBN().getName(), reduction, erode.getBN().getSpecies().size(), obtainedPartition.size()/(double)erode.getBN().getSpecies().size(), obtainedPartition.size(), -1, erode.getPartition().size(),obtainedPartition.size());
		try {
			erode.computeReducedModel(updateCRN, reduction, out, bwOut, 
					reducedFileName, printModels, aggr, obtainedPartition, 
					icWarning, reductionName, reducedModelName, smtFBE, 
					smtBBE, writeReducedCRN, infoReduction);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if(updateCRN) {
			populateAuxiliarySpeciesDataStructures(erode.getBN());
		}
	}
	
	
	
	

	private void completeLoadingBN(GuessPrepartitionBN guessPrep, String modelName,
			LinkedHashMap<String, IUpdateFunction> parsed) {
		BasicModelElementsCollector bMec = new BasicModelElementsCollector(guessPrep, parsed);
		GUIBooleanNetworkImporter bnImporter = new GUIBooleanNetworkImporter(false,null,null,null,false);
		bnImporter.importBooleanNetwork(true, false, true, modelName, bMec.getInitialConcentrations(), bMec.getBooleanUpdateFunctions(), bMec.getUserPartition(), null);
		IBooleanNetwork bn=bnImporter.getBooleanNetwork();
		//IPartition initial=bnImporter.getInitialPartition();
		defaultPartition=bnImporter.getInitialPartition();
		
		populateAuxiliarySpeciesDataStructures(bn);
		erode = new BooleanNetworkCommandLine(new CommandsReader(new ArrayList<String>(0),out,bwOut),bn,defaultPartition,false);
		bnLoaded=true;
		
		if(printModels){
			CRNReducerCommandLine.println(out,bwOut,erode.getBN());
		}
	}

	public void populateAuxiliarySpeciesDataStructures(IBooleanNetwork bn) {
		nameToSpecies=new LinkedHashMap<>(bn.getSpecies().size());
		idToSpecies=new ISpecies[bn.getSpecies().size()];
		idToSpeciesNames=new String[bn.getSpecies().size()];
		int i=0;
		for(ISpecies sp : bn.getSpecies()) {
			nameToSpecies.put(sp.getName(), sp);
			idToSpecies[i]=sp;
			idToSpeciesNames[i]=sp.getName();
			i++;
		}
	}

	private GuessPrepartitionBN handleGuessPrep(String guessPrepStr) {
		GuessPrepartitionBN guessPrep;
		if(guessPrepStr==null||guessPrepStr.length()==0) {
			guessPrepStr=GuessPrepartitionBN.INPUTS.name();
		}
		guessPrepStr=guessPrepStr.toUpperCase();
		try {
			guessPrep= GuessPrepartitionBN.valueOf(guessPrepStr);
		}
		catch(IllegalArgumentException e) {
			System.out.println("Prepartition option not supported ("+guessPrepStr+"). Please use any of:");
			for(GuessPrepartitionBN v:GuessPrepartitionBN.values()) {
				System.out.println("\t"+v);
			}
			return null;
		}
		return guessPrep;
	}
}
