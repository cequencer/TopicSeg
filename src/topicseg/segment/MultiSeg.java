/*
 * Copyright (C) 2010 Cluster of Excellence, Univ. of Saarland
 * Minwoo Jeong (minwoo.j@gmail.com) is a main developer.
 * This file is part of "TopicSeg" package.
 * This software is provided under the terms of LGPL.
 */

package topicseg.segment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import cern.jet.random.Uniform;
import cern.jet.random.engine.MersenneTwister;
import cern.jet.random.engine.RandomEngine;

import topicseg.document.*;
import topicseg.document.Annotation.Label;
import topicseg.model.*;
import topicseg.utils.Annealer;
import topicseg.utils.Eval;
import topicseg.utils.Option;

/**
 * unisaar.topicseg.segment::MultiSeg.java
 *
 * @author minwoo
 */
public class MultiSeg extends Segmenter {
	
	private transient Logger logger = Logger.getLogger(MultiSeg.class);
	
	// Language model
	double dirPriorOfGlobalLM;
	double dirPriorOfLocalLM;
	double dirPriorOfGlobalTP;
	double dirPriorOfLocalTP;
	double dpPrior;
	GammaCache gamma;

	// random generator & simulated annealing
	int initRandSeed;
	RandomEngine randomEngine;
	Annealer simAnnealer;
	Annealer simAnnealerGlobal;
	Annealer simAnnealerLocal;
	Annealer simAnnealerShift;
	double simAnnealerBurninDuration;
	double simAnnealerCoolingDuration;
	double simAnnealerMaxBurninTemp;
	
	// config variables
	boolean useGlobalMerge;
	boolean useLocalSwap;
	boolean useLocalAggressiveSplitMerge;
	int maxMcmcIter;
	int initGlobalMoveIter;
	int printPeriod;
	int maxCluster;
	int minCluster;
	boolean useVerbose;
	boolean useEvalData;
	boolean useTopicProp;
	boolean useInitUniform;

	static enum MoveType { SPLIT, MERGE, SWAP }

	// sample acceptance ratio
	static int nAccGlobal = 0, nRejGlobal = 0, nTotalGlobal = 0;
	static int nAccLocal = 0, nRejLocal = 0, nTotalLocal = 0;
    static int nAccShift = 0, nRejShift = 0, nTotalShift = 0;
	
	public MultiSeg() {
		//languageModel = new DirchletMultinomial();
	    // prepare distance-based move proposals
	    //initializeDistanceProposal();
	}
	
	public void initialize(Option config) {
		gamma = new GammaCache();
		
		this.dirPriorOfGlobalLM = config.contains("prior.dcm.global") ? config.getDouble("prior.dcm.global") : 1; // just in case
		this.dirPriorOfLocalLM = config.contains("prior.dcm.local") ? config.getDouble("prior.dcm.local") : 1;
		this.dirPriorOfGlobalTP = config.contains("prior.tp.global") ? config.getDouble("prior.tp.global") : 1; // just in case
		this.dirPriorOfLocalTP = config.contains("prior.tp.local") ? config.getDouble("prior.tp.local") : 1;
		this.dpPrior = config.contains("prior.dp") ? config.getDouble("prior.dp") : 0.1;
		this.initRandSeed = config.contains("rand.seed") ? config.getInteger("rand.seed") : 0;
		this.simAnnealerBurninDuration = config.contains("rand.burninDuration") ? config.getDouble("rand.burninDuration") : .25;
		this.simAnnealerCoolingDuration = config.contains("rand.coolingDuration") ? config.getDouble("rand.coolingDuration") : .25;
		this.simAnnealerMaxBurninTemp = config.contains("rand.maxBurninTemp") ? config.getDouble("rand.maxBurninTemp") : 5;
		this.useGlobalMerge = config.contains("sampler.globalMerge") ? config.getBoolean("sampler.globalMerge") : true;
		this.useLocalSwap = config.contains("sampler.localSwap") ? config.getBoolean("sampler.localSwap") : false;
		this.useLocalAggressiveSplitMerge = config.contains("sampler.localAggressive") ? config.getBoolean("sampler.localAggressive") : false;
		this.maxMcmcIter = config.contains("sampler.maxMcmcIter") ? config.getInteger("sampler.maxMcmcIter") : 100000;
		this.initGlobalMoveIter = config.contains("sampler.initGlobal") ? config.getInteger("sampler.initGlobal") : 10000;
		this.printPeriod = config.contains("sampler.printPeriod") ? config.getInteger("sampler.printPeriod") : 1000;
		this.maxCluster = config.contains("sampler.maxGlobalCluster") ? config.getInteger("sampler.maxGlobalCluster") : 0;
		this.minCluster = config.contains("sampler.minGlobalCluster") ? config.getInteger("sampler.minGlobalCluster") : 1;
		this.maxShiftMoves = config.contains("sampler.maxShiftMove") ? config.getInteger("sampler.maxShiftMove") : 20;
		this.maxSplitMoves = config.contains("sampler.maxSplitMove") ? config.getInteger("sampler.maxSplitMove") : 20;
		this.useVerbose = config.contains("print.verbose") ? config.getBoolean("print.verbose") : true;
		this.useEvalData = config.contains("corpus.useEval") ? config.getBoolean("corpus.useEval") : true;
		this.useTopicProp = config.contains("prior.useTopicProp") ? config.getBoolean("prior.useTopicProp") : false;
		this.useInitUniform = config.contains("sampler.initUniform") ? config.getBoolean("sampler.initUniform") : false;

		initializeDistanceProposal();
		initializeRandom(initRandSeed);
		
        if (config.contains("exp.log")) {
			try {
				Option.addFileLogger(logger, config.getString("exp.log"));
			}
			catch (IOException e) {
				e.printStackTrace();
			}
        }
	}
	
	public void initializeRandom(int randSeed) {
        // initializing random engine
        if (randSeed > 0) 
	        randomEngine = new MersenneTwister(randSeed);
        else {
	        Date thedate = new Date();
	        randomEngine = new MersenneTwister(thedate);
        }
	    Uniform.staticSetRandomEngine(randomEngine);
        simAnnealer = new Annealer(simAnnealerBurninDuration, simAnnealerCoolingDuration, simAnnealerMaxBurninTemp, maxMcmcIter); // annealer
        simAnnealerGlobal = new Annealer(simAnnealerBurninDuration, simAnnealerCoolingDuration, simAnnealerMaxBurninTemp, maxMcmcIter); // annealer
        simAnnealerLocal = new Annealer(simAnnealerBurninDuration, simAnnealerCoolingDuration, simAnnealerMaxBurninTemp, maxMcmcIter); // annealer
        simAnnealerShift = new Annealer(simAnnealerBurninDuration, simAnnealerCoolingDuration, simAnnealerMaxBurninTemp, maxMcmcIter); // annealer
	}

	public void run(DocumentSet docSet, double dirPriorOfGlobalLM, double dirPriorOfLocalLM) {
		// TODO Auto-generated method stub
	}

	public void run(Corpus corpus, double dirPriorOfGlobalLM, double dirPriorOfLocalLM) {
		segmentAll(corpus, dirPriorOfGlobalLM, dirPriorOfLocalLM);
	}
	
	private void initializeSegment(DocumentSet docSet) {
		initializeSegment(docSet, 1);
	}
	
	private void initializeSegment(DocumentSet docSet, int K) {
    	TopicModel globalTopic = docSet.getGlobalTopic();
    	int W = docSet.getAlphabet().size();
    	globalTopic.initialize(W, dirPriorOfGlobalLM, dirPriorOfGlobalTP); // TODO: this initialization would be moved into corpus loading or construction
    	
		int[] gTopicId = new int[K];
		for (int i = 0; i < K; i++) gTopicId[i] = globalTopic.addTopic();
		
    	for (Document doc : docSet) { 
    		TopicModel localTopic = doc.getTopic();
    		localTopic.initialize(W, dirPriorOfLocalLM, dirPriorOfGlobalTP);
    		
    		/// this should be modified
    		int T = doc.size();
    		int[] labels = new int[T];
    		Arrays.fill(labels, -1);
	        int segstart = 0, segend = 0; 
	        for (int k = 0; k < K; k++) {
	        	double val = (double)T / K;
	    		segstart = (int)Math.round(k * val); segend = (int)Math.round((k+1) * val);
	        	for (int i = segstart; i < segend && i < T; i++) {
	        		labels[i] = gTopicId[k];
	    			globalTopic.update(doc.getSentence(i), gTopicId[k], 1);
	        	}
	        }
	        for (int i = segend; i < T; i++)
	        	labels[i] = gTopicId[K -1];
    		doc.setTopicLabel(labels);
    		///
    		
    		for (int topicId : gTopicId) {
	    		doc.getPool().add(topicId);
	    		globalTopic.increaseRestaurant(topicId);
	        	globalTopic.recomputeLogProb(topicId, true);
    		}
    	}
    	
		//globalTopic.storeLogProb(gTopicId, languageModel.logDCM(globalTopic.getWordVector(gTopicId), W, dirPriorOfGlobalLM));
	}
	
	private void runSampler(DocumentSet docSet, boolean useLocalSampler) {
		TopicModel globalTopic = docSet.getGlobalTopic();
		
		//----------------------------
		// split-merge on the global
		int sampledTopicLabel1 = sampleSegmentByUniform(docSet);
		int sampledTopicLabel2 = sampleSegmentByUniform(docSet);
		// both samples must be global topics
		if (sampledTopicLabel1 >= 0 && sampledTopicLabel2 >= 0) { 
    		if (sampledTopicLabel1 == sampledTopicLabel2)
    			sampleGlobalSplit(docSet, sampledTopicLabel1);
    		else if (this.useGlobalMerge) 
    			sampleGlobalMerge(docSet, sampledTopicLabel1, sampledTopicLabel2);
		}
		
    	for (Document doc : docSet) { 
			if (useLocalSampler) {
    			//-----------------------------------
    			// split-merge on the local
				int nSegment = doc.nSegment();
				int sampledSegmentIdx1 = Uniform.staticNextIntFromTo(0, nSegment-1);
    			int sampledSegmentIdx2 = Uniform.staticNextIntFromTo(0, nSegment-1);
    			
    			if (sampledSegmentIdx1 == sampledSegmentIdx2) {
					sampleLocalSplit(globalTopic, doc, sampledSegmentIdx1);
    			} 
    			else if (Math.abs(sampledSegmentIdx1 - sampledSegmentIdx2) == 1) {
    				if (useLocalSwap) {
    					if (Uniform.staticNextDouble() < 0.5)
        					sampleLocalMerge(globalTopic, doc, sampledSegmentIdx1, sampledSegmentIdx2);
    					else
    						sampleLocalSwap(globalTopic, doc, sampledSegmentIdx1, sampledSegmentIdx2);
    				}
    				else
    					sampleLocalMerge(globalTopic, doc, sampledSegmentIdx1, sampledSegmentIdx2);
    			} else { // swap
					if (useLocalSwap)
						sampleLocalSwap(globalTopic, doc, sampledSegmentIdx1, sampledSegmentIdx2);
    			}
			}
			
			if (doc.nSegment() < 2) 
				continue;
			
			// shift move
			sampleLocalShift(globalTopic, doc);
    	}
	}
	
	protected void segmentAll(Corpus corpus, double dirPriorOfGlobalLM, double dirPriorOfLocalLM) {
		// initialization 
    	long timer = System.currentTimeMillis(); // timer
        ArrayList<Integer> docids = new ArrayList<Integer>(); // document set IDs for random permutation
        for (int d = 0; d < corpus.size(); d++) docids.add(d);
        // acceptance rate
        nAccGlobal = 0; nRejGlobal = 0; nTotalGlobal = 0;
        nAccLocal = 0; nRejLocal = 0; nTotalLocal = 0;
        nAccShift = 0; nRejShift = 0; nTotalShift = 0;
        // prior re-assignment
        this.dirPriorOfGlobalLM = dirPriorOfGlobalLM; this.dirPriorOfLocalLM = dirPriorOfLocalLM;
		// initialize with local topic cluster
        
        if (useInitUniform) {
	        for (DocumentSet docSet : corpus) {
	        	int kcluster = docSet.getAnnotation().size();
	        	initializeSegment(docSet, kcluster);
	        }
        } else {
	        for (DocumentSet docSet : corpus) 
	        	initializeSegment(docSet, this.minCluster);
        }
        
        print(corpus, 0, timer);
        
	    // Metropolis-Hastings Split-Merge sampler
	    for (int iter = 1; iter <= maxMcmcIter; iter++) {
	    	Collections.shuffle(docids);
	    	
	    	for (int docId = 0; docId < corpus.size(); docId++) {
	    		DocumentSet docSet = corpus.get(docids.get(docId));
	    		runSampler(docSet, iter > initGlobalMoveIter ? true : false);
	    	}
            simAnnealer.update(); simAnnealerGlobal.update(); simAnnealerLocal.update(); simAnnealerShift.update(); 
            
    		if (printPeriod > 0 && (iter % printPeriod == 0)) {
    			print(corpus, iter, timer);
    		}
	    }
	    
	    // print the reference and hypothesis
	    if (useVerbose) {
	        for (DocumentSet docSet : corpus) {
	        	Annotation annotation = docSet.getAnnotation();
	        	if (annotation.size() > 0) {
		        	int[][] refs = annotation.toArray();
		        	for (Document doc : docSet) {
		        		int[] hyp = doc.getTopicLabel();
		                StringBuffer sb = new StringBuffer();
		        		sb.append("[" + docSet.getId() + "," + doc.getId() + "] ");
		        		for (int x = 0; x < hyp.length; x++)
		        			sb.append(refs[doc.getId()][x] + ":" + hyp[x] + " ");
		        		logger.info(sb);
		        	}
	        	}
	        }
	    }
        
	}
	
	private void print(Corpus corpus, int iter, long timer) {
	    // print out
		if (useEvalData) {
			double[] evalMetrics = evaluate(corpus);
			double tookTime = ((long)System.currentTimeMillis() - timer);
			logger.info(String.format("sample=%d || loglike=%e %.2f%%/%.2f%%/%.2f%% p=%.4f/r=%.4f/f1=%.4f vi=%.4f ri=%.4f pk=%.4f wd=%.4f time=%.2f nSeg=%.2f", 
					iter, 
					computeCorpusLogLikelihood(corpus, dirPriorOfGlobalLM, dirPriorOfLocalLM), 
					(double)nAccGlobal / nTotalGlobal * 100,
					(double)nAccLocal / nTotalLocal * 100,
					(double)nAccShift / nTotalShift *100,
					evalMetrics[5], evalMetrics[6], evalMetrics[7],
					evalMetrics[8], evalMetrics[9], 
					evalMetrics[1], evalMetrics[2],
					tookTime / 1000,
					evalMetrics[4]
					));
		} else {
			double tookTime = ((long)System.currentTimeMillis() - timer);
			logger.info(String.format("sample=%d || loglike=%.0f %.2f%%/%.2f%%/%.2f%% time=%.2f", 
					iter, 
					computeCorpusLogLikelihood(corpus, dirPriorOfGlobalLM, dirPriorOfLocalLM), 
					(double)nAccGlobal / nTotalGlobal * 100,
					(double)nAccLocal / nTotalLocal * 100,
					(double)nAccShift / nTotalShift *100,
					tookTime / 1000
					));
		}
		
		nTotalGlobal = 0; nAccGlobal = 0; nRejGlobal = 0; 
		nTotalLocal = 0; nAccLocal = 0; nRejLocal = 0; 
		nTotalShift = 0; nAccShift = 0; nRejShift = 0; 
	}
	
	private void sampleGlobalSplit(DocumentSet docSet, int gTopicId) {
		TopicModel globalTopic = docSet.getGlobalTopic();
		if (docSet.maxGlobalCluster > 0 && globalTopic.size() >= docSet.maxGlobalCluster)
			return;
		if (globalTopic.nRestaurant(gTopicId) < 2)
			return;
		
//		SegmentInfo[] segments = new SegmentInfo[docSet.size()];
//		int[] segIdx = new int[docSet.size()];
//		
//		int coin = Uniform.staticNextIntFromTo(0, 1);
//		boolean isAllAreSplitable = true;
//		for (Document doc : docSet) {
//			int idx = doc.getId();
//			segIdx[idx] = doc.whereTopic(gTopicId);
//			if (segIdx[idx] < 0) continue;
//			
//			segments[idx] = new SegmentInfo(globalTopic, doc, segIdx[idx]);
//			if (segments[idx].endPos - segments[idx].startPos < 2) {
//				isAllAreSplitable = false;
//				break;
//			}
//		}
//		
//		if (!isAllAreSplitable)
//			return;
		
		int W = globalTopic.nVocab();
		int D = docSet.size();
		
		int old_n_dish = globalTopic.nDish(gTopicId);
		double old_logprob = globalTopic.getLogProb(gTopicId); 
		if (this.useTopicProp) old_logprob += globalTopic.topicPropotional(); //dcm.logDCM(globalTopic.getWordVector(gTopic), W, dirPriorOfGlobalLM);
		int[] position = new int[D];
		int[] start_pos = new int[D];
		int[] end_pos = new int[D];
		int[] move_pos = new int[D];
		//int count = 0;
		int coin = Uniform.staticNextIntFromTo(0, 1);
		
		boolean isAvailable = true;
		for (int docId = 0; docId < D; docId++) {
			Document doc = docSet.get(docId);
			old_logprob += doc.logPriorOfTopicType();
			
			position[docId] = doc.whereTopic(gTopicId);
			if (position[docId] < 0) continue;
			
			int segptStart = doc.getSegmentStartPos(position[docId]);
			int segptEnd = doc.getSegment(position[docId]);
			if (segptEnd - segptStart < 2) {
				isAvailable = false;
				break;
			}
			// sample a boundary position 
			//double[] split_move = getSplitMove(doc, position[d]);
			//move_pos[d] = (int)split_move[0];
			move_pos[docId] =  segptStart + (segptEnd - segptStart) / 2;
			start_pos[docId] = coin > 0 ? segptStart : move_pos[docId];
			end_pos[docId] = coin > 0 ? move_pos[docId] : segptEnd;
		}
		
		if (!isAvailable) 
			return;
		
		//System.out.print(globalTopic.topicPropotional());
		// new global topic
		int newGlobalTopicId = globalTopic.addTopic(); 
		for (int docId = 0; docId < D; docId++) {
			Document doc = docSet.get(docId);
			if (position[docId] < 0) continue;
			updateSegment(globalTopic, gTopicId, newGlobalTopicId, doc, start_pos[docId], end_pos[docId]);
			doc.getPool().add(newGlobalTopicId);
			globalTopic.increaseRestaurant(newGlobalTopicId);
		}
		//System.out.println(" " + globalTopic.topicPropotional());
		
		int new_n_dish1 = globalTopic.nDish(gTopicId);
		int new_n_dish2 = globalTopic.nDish(newGlobalTopicId);
		if (new_n_dish2 == 0) {
        	for (int d = 0; d < D; d++) {
        		Document doc = docSet.get(d);
				doc.getPool().remove(newGlobalTopicId);
        	}
			globalTopic.removeTopic(newGlobalTopicId);
            nRejGlobal++;
            nTotalGlobal++;
            return;
		}
		
		//if (old_n_dish != new_n_dish1 + new_n_dish2)
		//	System.out.println(gTopicId + ":" + old_n_dish + " " + new_n_dish1 + " " + new_n_dish2);
		assert(old_n_dish == new_n_dish1 + new_n_dish2);
		
        //double logprob_of_segment1 = languageModel.logDCM(globalTopic.getWordVector(gTopicId), W, dirPriorOfGlobalLM);
        //double logprob_of_segment2 = languageModel.logDCM(globalTopic.getWordVector(newGlobalTopicId), W, dirPriorOfGlobalLM);
        double logprob_of_segment1 = globalTopic.recomputeLogProb(gTopicId);
        double logprob_of_segment2 = globalTopic.recomputeLogProb(newGlobalTopicId);
        double new_logprob = logprob_of_segment1 + logprob_of_segment2;
        if (this.useTopicProp) new_logprob += globalTopic.topicPropotional();
        //for (Document doc : docSet)
        //	new_logprob += doc.logPriorOfTopicType() + doc.getTopic().topicPropotional();

		//double prior_ratio = dpPrior * cern.jet.math.Arithmetic.factorial(new_n_dish1 - 1) * cern.jet.math.Arithmetic.factorial(new_n_dish2 - 1) / cern.jet.math.Arithmetic.factorial(old_n_dish - 1);
		double logDPPriorRatio = Math.log(dpPrior) + 
			gamma.logGamma(new_n_dish1 - 1) + gamma.logGamma(new_n_dish2 - 1) - 
			gamma.logGamma(old_n_dish - 1);
		double proposal_ratio = 1.0 / (Math.pow(0.5, new_n_dish1 + new_n_dish2 - 2));
        double p_accept = Math.exp(new_logprob - old_logprob + logDPPriorRatio) * proposal_ratio;
        p_accept = simAnnealerGlobal.annealWithoutUpdate(p_accept);
        if (p_accept > 0 && Uniform.staticNextDouble() < p_accept) {
        	globalTopic.storeLogProb(gTopicId, logprob_of_segment1);
    		globalTopic.storeLogProb(newGlobalTopicId, logprob_of_segment2);
            nAccGlobal++;
        } else {
        	for (int docId = 0; docId < D; docId++) {
        		Document doc = docSet.get(docId);
    			if (position[docId] < 0) continue;
        		for (int t = start_pos[docId]; t < end_pos[docId]; t++) {
    				globalTopic.update(doc.getSentence(t), gTopicId, 1);
    				doc.setTopicLabel(t, gTopicId);
    				doc.getPool().remove(newGlobalTopicId);
        		}
    			doc.resetTopicLabel();
        	}
			globalTopic.removeTopic(newGlobalTopicId);
            nRejGlobal++;
        }
        nTotalGlobal++;
	}
	
	private void sampleGlobalMerge(DocumentSet docSet, int sampledTopicId1, int sampledTopicId2) {
		TopicModel globalTopic = docSet.getGlobalTopic();
//		if (minCluster > 0 && minCluster >= globalTopic.size())
//			return;
		
		int D = docSet.size();
		SegmentInfo[] winnerSeg = new SegmentInfo[D];
		SegmentInfo[] looserSeg = new SegmentInfo[D];
		
		int[] winnerPosIdx = new int[D];
		int[] looserPosIdx = new int[D];
		
		HashSet<Integer> vaildTopicId = new HashSet<Integer>();
		
		boolean isAvailable = true;
		for (int docId = 0; docId < D; docId++) {
			Document doc = docSet.get(docId);
			winnerPosIdx[docId] = doc.whereTopic(sampledTopicId1);
			looserPosIdx[docId] = doc.whereTopic(sampledTopicId2);
			if (winnerPosIdx[docId] < 0 && looserPosIdx[docId] >= 0) {
				if (looserPosIdx[docId] > 0) winnerPosIdx[docId] = looserPosIdx[docId] - 1;
				else if (looserPosIdx[docId] < doc.nSegment() - 1 ) winnerPosIdx[docId] = looserPosIdx[docId] + 1;
				else { 
					isAvailable = false;
					break;
				}
			}
			if (/*winnerPosIdx[docId] < 0 ||*/ Math.abs(winnerPosIdx[docId] - looserPosIdx[docId]) != 1) {
				isAvailable = false;
				break;
			}
			if (looserPosIdx[docId] < 0)
				continue;
			
			winnerSeg[docId] = new SegmentInfo(globalTopic, doc, winnerPosIdx[docId]);
			looserSeg[docId] = new SegmentInfo(globalTopic, doc, looserPosIdx[docId]);
			vaildTopicId.add(winnerSeg[docId].topicId);
			
			if (winnerSeg[docId].topicLabel < 0)
				isAvailable = false;
		}
		if (!isAvailable) 
			return;

//		int old_n_dish1 = globalTopic.nDish(sampledTopicId1);
//		int old_n_dish2 = globalTopic.nDish(sampledTopicId2);
//		double old_logprob = globalTopic.getLogProb(sampledTopicId1) + globalTopic.getLogProb(sampledTopicId2);
//		if (this.useTopicProp) old_logprob += globalTopic.topicPropotional(); 
		int old_n_dish1 = 0;
		int old_n_dish2 = globalTopic.nDish(sampledTopicId2);
		double old_logprob = globalTopic.getLogProb(sampledTopicId2);
		if (this.useTopicProp) old_logprob += globalTopic.topicPropotional();
		for (int id : vaildTopicId) {
			old_n_dish1 += globalTopic.nDish(id);
			old_logprob += globalTopic.getLogProb(id);
		}
		
		// merge
		for (int docId = 0; docId < D; docId++) {
			if (looserSeg[docId] == null) continue;
			old_logprob += docSet.get(docId).logPriorOfTopicType();
			looserSeg[docId].decreaseWordCount();
			winnerSeg[docId].increaseWordCount(looserSeg[docId].segStartPos, looserSeg[docId].segEndPos);
			//new_logprob += docSet.get(docId).logPriorOfTopicType();
		}
		
//		int new_n_dish = globalTopic.nDish(sampledTopicId1);
//		//double logprob_of_segment = languageModel.logDCM(globalTopic.getWordVector(sampledTopicId1), globalTopic.nVocab(), this.dirPriorOfGlobalLM);
//		double logprob_of_segment = globalTopic.recomputeLogProb(sampledTopicId1);
//		double new_logprob = logprob_of_segment;
//		if (this.useTopicProp) new_logprob += globalTopic.topicPropotional();
		int new_n_dish = 0; 
		double new_logprob = 0; 
		if (this.useTopicProp) new_logprob += globalTopic.topicPropotional();
		for (int id : vaildTopicId) {
			new_n_dish += globalTopic.nDish(id);
			new_logprob += globalTopic.recomputeLogProb(id);
		}
 
		assert(new_n_dish == old_n_dish1 + old_n_dish2);
		
		// accept or reject
		double logDPPriorRatio = -Math.log(dpPrior) + gamma.logGamma(new_n_dish - 1) - gamma.logGamma(old_n_dish1 - 1) - gamma.logGamma(old_n_dish2 - 1);
		double proposal_ratio = Math.pow(0.5, old_n_dish1 + old_n_dish2 - 2);
		double p_accept = Math.exp(new_logprob - old_logprob + logDPPriorRatio) * proposal_ratio;
		p_accept = simAnnealerGlobal.annealWithoutUpdate(p_accept);
		if (p_accept > 0 && Uniform.staticNextDouble() < p_accept) {
			//System.out.println(p_accept + " " +Math.exp(new_logprob - old_logprob) + " " +  new_n_dish + "/" + old_n_dish1 + "/" + old_n_dish2 + " " +Math.exp(logDPPriorRatio) + " " + proposal_ratio);
			for (int id : vaildTopicId)
				globalTopic.storeLogProb(id, globalTopic.recomputeLogProb(id, true));
	    	globalTopic.removeTopic(sampledTopicId2);
	        nAccGlobal++;
	    } else {
	    	for (int docId = 0; docId < D; docId++) {
				if (looserSeg[docId] == null) continue;
				winnerSeg[docId].decreaseWordCount(looserSeg[docId].segStartPos, looserSeg[docId].segEndPos);
				looserSeg[docId].increaseWordCount();
	    	}
	        nRejGlobal++;
	    }
	    nTotalGlobal++;
	}

	private void sampleLocalSplit(TopicModel globalTopic, Document doc, int randomSegmentIdx) {
		SegmentInfo segment = new SegmentInfo(globalTopic, doc, randomSegmentIdx);
		if (segment.segEndPos - segment.segStartPos < 2)
			return;
		
		// sample a boundary position
		double[] splitMove = sampleSplitMoveTransform(doc, randomSegmentIdx);
		int start, end;
		if (Uniform.staticNextIntFromTo(0, 1) > 0) {
			start = segment.segStartPos; end = (int)splitMove[0];
		} else {
			start = (int)splitMove[0]; end = segment.segEndPos;
		}
		
		// a new segment
		SegmentInfo splittedSegment = null;

		// global or local?
		TopicType newTopicType = TopicType.LOCAL; //(Uniform.staticNextIntFromTo(0, 1) > 0) ? TopicType.LOCAL : TopicType.GLOBAL;
		
		int newGlobalTopicId = -1;
		if (this.useLocalAggressiveSplitMerge) {
			newGlobalTopicId = sampleGlobalTopicId(globalTopic, doc);
			if (newGlobalTopicId >= 0) {
				splittedSegment = new SegmentInfo(globalTopic, doc, TopicType.GLOBAL, newGlobalTopicId);
				newTopicType = TopicType.GLOBAL;
			}
			else
				splittedSegment = new SegmentInfo(globalTopic, doc, TopicType.LOCAL);
		}
		else 
			splittedSegment = new SegmentInfo(globalTopic, doc, TopicType.LOCAL);
		
		splittedSegment.setPosition(start, end);
		
		// stop condition
		if (newTopicType == TopicType.LOCAL && doc.getDocPrior().getLocalTypePrior() == 0) {
        	splittedSegment.topicModel.removeTopic(splittedSegment.topicId);
			return;
		}
		
		// update
		segment.decreaseWordCount(start, end); splittedSegment.increaseWordCount();
		segment.refresh(); splittedSegment.refresh();
		
		// accept or reject
		double acceptRatio = computeProposalProb(segment, splittedSegment, newTopicType == TopicType.LOCAL ? MoveType.SPLIT : MoveType.SWAP, splitMove[1]);
        if (acceptRatio > 0 && Uniform.staticNextDouble() < acceptRatio) {
        	segment.accept(); splittedSegment.accept();
        	if (newTopicType == TopicType.GLOBAL) {
           		doc.getPool().add(splittedSegment.topicId);
           		splittedSegment.topicModel.increaseRestaurant(splittedSegment.topicId);
        	}
            nAccLocal++;
        } else {
        	if (newTopicType == TopicType.GLOBAL)
        		splittedSegment.decreaseWordCount();
        	else
            	splittedSegment.topicModel.removeTopic(splittedSegment.topicId);
        	segment.increaseWordCount(start, end);
            nRejLocal++;
        }
        nTotalLocal++;
	}
	
	private int sampleGlobalTopicId(TopicModel globalTopic, Document doc) {
		// select one of global topics
		Set<Integer> totalIds = globalTopic.getIds();
		Set<Integer> containIds = doc.getPool();
		ArrayList<Integer> tmp = new ArrayList<Integer>();
		for (int key : totalIds)
			if (!containIds.contains(key))
				tmp.add(key);
		
		if (tmp.size() < 1)
			return -1;
		
		int id = tmp.get(Uniform.staticNextIntFromTo(0, tmp.size()-1)); // ~Uniform, might be ~P(z|...)
		return id;
	}

	private void sampleLocalMerge(TopicModel globalTopic, Document doc, int randomSegmentIdx1, int randomSegmentIdx2) {
		SegmentInfo winnerSeg = new SegmentInfo(globalTopic, doc, randomSegmentIdx1);
		SegmentInfo looserSeg = new SegmentInfo(globalTopic, doc, randomSegmentIdx2);
		
		// global topic should be in 2 documents at least.
		if (looserSeg.topicType == TopicType.GLOBAL && (looserSeg.topicModel.nRestaurant(looserSeg.topicId) <= 2 || !useLocalAggressiveSplitMerge))
			return;
		// stop condition
		if (winnerSeg.topicType == TopicType.LOCAL && doc.getDocPrior().getLocalTypePrior() == 0) 
			return;
		
		//Document doc = docSet.get(docId);
		
//		// select the removable topic
//		SegmentInfo winnerSeg = null, looserSeg = null;
//		if (seg2.topicType.equals(TopicType.GLOBAL)) {
//			winnerSeg = seg2; looserSeg = seg1;
//		} else {
//			winnerSeg = seg1; looserSeg = seg2;
//		}
			
		// update
		looserSeg.decreaseWordCount();
		winnerSeg.increaseWordCount(looserSeg.segStartPos, looserSeg.segEndPos);
		winnerSeg.refresh(); 
		if (looserSeg.topicType == TopicType.GLOBAL)
			looserSeg.refresh();
		
		// accept or reject
		double acceptRatio = computeProposalProb(winnerSeg, looserSeg, looserSeg.topicType == TopicType.LOCAL ? MoveType.MERGE : MoveType.SWAP, 1.0);
        if (acceptRatio > 0 && Uniform.staticNextDouble() < acceptRatio) {
        	winnerSeg.accept();
        	if (looserSeg.topicType == TopicType.GLOBAL) {
        		looserSeg.accept();
        		doc.getPool().remove(looserSeg.topicId);
        		looserSeg.topicModel.decreseRestaurant(looserSeg.topicId);
        	}
        	else 
        		looserSeg.topicModel.removeTopic(looserSeg.topicId);
            nAccLocal++;
        } else {
        	winnerSeg.decreaseWordCount(looserSeg.segStartPos, looserSeg.segEndPos);
        	looserSeg.increaseWordCount();
            nRejLocal++;
        }
        nTotalLocal++;
	}		

	
	private void sampleLocalShift(TopicModel globalTopic, Document doc) {
		//Document doc = docSet.get(docId);
		int nSegs = doc.nSegment();
		int randomSegment = Uniform.staticNextIntFromTo(0, nSegs-2);
		double[] shift_move = sampleShiftMoveTransform(doc, randomSegment);
		int move = (int) shift_move[0];
        if (move == 0) return;

        SegmentInfo prevSegment = new SegmentInfo(globalTopic, doc, randomSegment);
        SegmentInfo nextSegment = new SegmentInfo(globalTopic, doc, randomSegment+1);
        
        // boundary update
        int segpt = doc.getSegment(randomSegment);
        int start = move > 0 ? segpt : segpt + move;
        int end = move > 0 ? start+move : segpt;
        if (move > 0) {
        	nextSegment.decreaseWordCount(start, end);
        	prevSegment.increaseWordCount(start, end, prevSegment.topicLabel);
        }
        else {
        	prevSegment.decreaseWordCount(start, end);
        	nextSegment.increaseWordCount(start, end, nextSegment.topicLabel);
        }
        prevSegment.refresh();
        nextSegment.refresh();
        
        // accept or reject
        double old_logprob = prevSegment.logProbOfDocOld + nextSegment.logProbOfDocOld; //+ prevSegment.logProbOfTopicOld + nextSegment.logProbOfTopicOld + prevSegment.logProbOfDocPriorOld;
        double new_logprob = prevSegment.logProbOfDocNew + nextSegment.logProbOfDocNew; // + prevSegment.logProbOfTopicNew + nextSegment.logProbOfTopicNew + prevSegment.logProbOfDocPriorNew;
        double acceptRatio = Math.exp(new_logprob - old_logprob) * shift_move[1];
        acceptRatio = simAnnealerShift.annealWithoutUpdate(acceptRatio);
        if (acceptRatio > 0 && Uniform.staticNextDouble() < acceptRatio) {
        	prevSegment.accept(); nextSegment.accept();
            nAccShift++;
        } else {
            if (move > 0) {
            	prevSegment.decreaseWordCount(start, end);
            	nextSegment.increaseWordCount(start, end, nextSegment.topicLabel);
            }
            else {
            	nextSegment.decreaseWordCount(start, end);
            	prevSegment.increaseWordCount(start, end, prevSegment.topicLabel);
            }
            nRejShift++;
        }
        nTotalShift++;
	}

	private void sampleLocalSwap(TopicModel globalTopic, Document doc, int randomSegmentIdx1, int randomSegmentIdx2) {
		SegmentInfo seg1 = new SegmentInfo(globalTopic, doc, randomSegmentIdx1);
		SegmentInfo seg2 = new SegmentInfo(globalTopic, doc, randomSegmentIdx2);
		
		if (seg1.topicType.equals(TopicType.LOCAL) && seg2.topicType.equals(TopicType.LOCAL))
			return;
		
		// update
		seg1.decreaseWordCount(); seg2.decreaseWordCount();
		seg1.increaseWordCount(seg2.segStartPos, seg2.segEndPos, seg1.topicLabel);
		seg2.increaseWordCount(seg1.segStartPos, seg1.segEndPos, seg2.topicLabel);
		seg1.refresh(); seg2.refresh();
		
		// accept or reject
		double acceptRatio = computeProposalProb(seg1, seg2, MoveType.SWAP, 1.0);
        if (acceptRatio > 0 && Uniform.staticNextDouble() < acceptRatio) {
        	seg1.accept(); seg2.accept();
            nAccLocal++;
        } else {
        	seg1.decreaseWordCount(seg2.segStartPos, seg2.segEndPos); seg2.decreaseWordCount(seg1.segStartPos, seg1.segEndPos);
        	seg1.increaseWordCount(seg1.topicLabel); seg2.increaseWordCount(seg2.topicLabel);
            nRejLocal++;
        }
        nTotalLocal++;
	}			

	private double computeProposalProb(SegmentInfo seg1, SegmentInfo seg2, MoveType moveType, double distProp) {
		double logDPPriorRatio = computeDPPriorRatio(seg1.nDishNew, seg2.nDishNew, seg1.nDishOld, seg2.nDishOld, moveType);
		double proposalRatio = computeProposalRatio(seg1.nDishNew, seg2.nDishNew, seg1.nDishOld, seg2.nDishOld, moveType);
		
		double oldLogprob = moveType.equals(MoveType.SPLIT) ? seg1.logProbOfDocOld : seg1.logProbOfDocOld + seg2.logProbOfDocOld;
		double newLogprob = moveType.equals(MoveType.MERGE) ? seg1.logProbOfDocNew : seg1.logProbOfDocNew + seg2.logProbOfDocNew;
		double logProbRatio = newLogprob + seg1.logProbOfDocPriorNew /*+ seg1.logProbOfTopicNew + seg2.logProbOfTopicNew*/  
				- oldLogprob - seg1.logProbOfDocPriorOld;// - seg1.logProbOfTopicOld - seg2.logProbOfTopicOld;
		//System.out.println(Math.exp(seg1.logProbOfTopicNew + seg2.logProbOfTopicNew - seg1.logProbOfTopicOld - seg2.logProbOfTopicOld));
		double probAccept = Math.exp(logProbRatio + logDPPriorRatio ) * proposalRatio * distProp;
		probAccept = simAnnealerLocal.annealWithoutUpdate(probAccept);
		
		return probAccept;
	}	
	
	private double computeDPPriorRatio(int nDishNew1, int nDishNew2, int nDishOld1, int nDishOld2, MoveType moveType) {
		double ratio = 1.;
		if (moveType.equals(MoveType.MERGE)) 
			//ratio = 1. / dpPrior * cern.jet.math.Arithmetic.factorial(nDishNew1 - 1) /
			//		(cern.jet.math.Arithmetic.factorial(nDishOld1 - 1) 	* cern.jet.math.Arithmetic.factorial(nDishOld2 - 1));
			ratio = -Math.log(dpPrior) + gamma.logGamma(nDishNew1 - 1) - gamma.logGamma(nDishOld1 - 1) - gamma.logGamma(nDishOld2 - 1);
		else if (moveType.equals(MoveType.SPLIT))
			//ratio = dpPrior * cern.jet.math.Arithmetic.factorial(nDishNew1 - 1) * cern.jet.math.Arithmetic.factorial(nDishNew2 - 1) /
			//		cern.jet.math.Arithmetic.factorial(nDishOld1 - 1);
			ratio = Math.log(dpPrior) + gamma.logGamma(nDishNew1 - 1) + gamma.logGamma(nDishNew2 - 1) - gamma.logGamma(nDishOld1 - 1);
		else  
			//ratio = cern.jet.math.Arithmetic.factorial(nDishNew1 - 1) * cern.jet.math.Arithmetic.factorial(nDishNew2 - 1) /
			//		(cern.jet.math.Arithmetic.factorial(nDishOld1 - 1) 	* cern.jet.math.Arithmetic.factorial(nDishOld2 - 1));
			ratio = gamma.logGamma(nDishNew1 - 1) + gamma.logGamma(nDishNew2 - 1) - gamma.logGamma(nDishOld1 - 1) - gamma.logGamma(nDishOld2 - 1);
		return ratio;
	}
	
	private double computeProposalRatio(int nDishNew1, int nDishNew2, int nDishOld1, int nDishOld2, MoveType moveType) {
		double ratio = 1.;
		if (moveType.equals(MoveType.MERGE))
			ratio = Math.pow(0.5, nDishOld1 + nDishOld2 - 2);
		else if (moveType.equals(MoveType.SPLIT))
			ratio = 1. / Math.pow(0.5, nDishNew1 + nDishNew2 - 2);
		//else
		//	ratio = Math.pow(0.5, nDishOld1 + nDishOld2 - nDishNew1 - nDishNew2);
		return ratio;
	}
	
	private enum TopicType { GLOBAL, LOCAL }

	private class SegmentInfo {
		public TopicModel topicModel;
		public Document doc;
		public int topicId;
		public TopicType topicType;
		public int topicLabel;
		public int nVocab;
		public double dcmPrior;
		public int segStartPos;
		public int segEndPos;
		public int size;
		public boolean isNewTopic;
		public double logProbOfDocPriorOld;
		public double logProbOfDocPriorNew;
		public int nDishOld;
		public int nDishNew;
		public double logProbOfDocOld;
		public double logProbOfDocNew;
		public double logProbOfTopicOld;
		public double logProbOfTopicNew;
		
		SegmentInfo(TopicModel globalTopic, Document doc, int segmentIdx) {
			this.doc = doc;
			topicLabel = doc.getLabel(segmentIdx);
			topicType = (topicLabel < 0) ? TopicType.LOCAL : TopicType.GLOBAL;
			if (topicType.equals(TopicType.LOCAL)) {
				topicId = topicLabel * -1 - 1;
				topicModel = doc.getTopic();
				dcmPrior = dirPriorOfLocalLM;
			}
			else {
				topicId = topicLabel;
				topicModel = globalTopic;
				dcmPrior = dirPriorOfGlobalLM;
			}
			
			segStartPos = doc.getSegmentStartPos(segmentIdx);
			segEndPos = doc.getSegment(segmentIdx);
			size = segEndPos - segStartPos;
			
			nVocab = topicModel.nVocab();
			nDishOld = topicModel.nDish(topicId);
			logProbOfDocOld = topicModel.getLogProb(topicId); //dcm.logDCM(topic.getWordVector(topicId), W, prior);
			isNewTopic = false;
			
			logProbOfDocPriorOld = doc.logPriorOfTopicType();
			logProbOfTopicOld = topicModel.topicPropotional();
		}
		
		SegmentInfo(TopicModel globalTopic, Document doc, TopicType topicType) {
			this.doc = doc;
			this.topicType = topicType;
			if (topicType == TopicType.LOCAL) {
				topicModel = doc.getTopic(); //docSet.getLocalTopic(docId);
				topicId = topicModel.addTopic();
				topicLabel = topicId * -1 - 1;
				dcmPrior = dirPriorOfLocalLM;
			}
			else {
				topicModel = globalTopic; //docSet.getGlobalTopic();
				topicId = topicModel.addTopic();
				topicLabel = topicId;
				dcmPrior = dirPriorOfGlobalLM;
			}
			
			nVocab = topicModel.nVocab();
			nDishOld = topicModel.nDish(topicId);
			logProbOfDocOld = topicModel.getLogProb(topicId); //dcm.logDCM(topic.getWordVector(topicId), W, prior);
			isNewTopic = true;
			logProbOfTopicOld = topicModel.topicPropotional();
		}
		
		SegmentInfo(TopicModel globalTopic, Document doc, TopicType topicType, int topicId) {
			this.doc = doc;
			this.topicType = topicType;
			this.topicId = topicId;
			if (topicType == TopicType.LOCAL) {
				topicModel = doc.getTopic(); //docSet.getLocalTopic(docId);
				topicLabel = topicId * -1 - 1;
				dcmPrior = dirPriorOfLocalLM;
			}
			else {
				topicModel = globalTopic; //docSet.getGlobalTopic();
				topicLabel = topicId;
				dcmPrior = dirPriorOfGlobalLM;
			}
			
			nVocab = topicModel.nVocab();
			nDishOld = topicModel.nDish(topicId);
			logProbOfDocOld = topicModel.getLogProb(topicId); //dcm.logDCM(topic.getWordVector(topicId), W, prior);
			isNewTopic = false;
			logProbOfTopicOld = topicModel.topicPropotional();
		}
		
		public void setPosition(int start, int end) {
			segStartPos = start;
			segEndPos = end;
			size = segEndPos - segStartPos;
		}
		
		public void decreaseWordCount(int start, int end) {
			for (int t = start; t < end; t++) 
				topicModel.update(doc.getSentence(t), topicId, -1);
		}
		
		public void increaseWordCount(int start, int end, int label) {
			int topic_id = (topicLabel < 0) ? topicLabel * -1 - 1 : topicLabel;
			for (int t = start; t < end; t++) {
				topicModel.update(doc.getSentence(t), topic_id, 1);
				doc.setTopicLabel(t, label);
			}
			doc.resetTopicLabel();
		}
		
		public void decreaseWordCount() { decreaseWordCount(segStartPos, segEndPos); }
		public void increaseWordCount(int start, int end) { increaseWordCount(start, end, topicLabel); }
		public void increaseWordCount(int label) { increaseWordCount(segStartPos, segEndPos, label); }
		public void increaseWordCount() { increaseWordCount(segStartPos, segEndPos, topicLabel); }
		
		public void refresh() {
			nDishNew = topicModel.nDish(topicId);
			//logProbOfDocNew = languageModel.logDCM(topicModel.getWordVector(topicId), nVocab, dcmPrior);
			logProbOfDocNew = topicModel.recomputeLogProb(topicId);
			logProbOfDocPriorNew = doc.logPriorOfTopicType();
			logProbOfTopicNew = topicModel.topicPropotional();
		}
		
		public void accept() { 
			topicModel.storeLogProb(topicId, logProbOfDocNew);
//			if (topicType.equals(TopicType.GLOBAL) && doc.getPool().contains(topicId)) { 
//				topic.increaseRestaurant(topicId);
//				doc.getPool().add(topicId);
//			}
		}
		
	}	
	
    protected double computeCorpusLogLikelihood(Corpus corpus, double dirPriorOfGlobalLM, double dirPriorOfLocalLM) {
    	if (dirPriorOfGlobalLM == 0 || dirPriorOfLocalLM == 0)
    		return -Double.MAX_VALUE;
    	
    	double logprob = 0;
    	for (DocumentSet docSet : corpus) {
    		TopicModel globalTopic = docSet.getGlobalTopic();
    		//int W = globalTopic.nVocab();
			//logprob += globalTopic.getLogProb();
    		//for (int topic_id : globalTopic.getIds()) {
    			//logprob += languageModel.logDCM(globalTopic.getWordVector(topic_id), W, dirPriorOfGlobalLM);
    			//logprob += globalTopic.recomputeLogProb(topic_id);
    			//logprob += globalTopic.getLogProb(topic_id);
    		//}
    		logprob += globalTopic.getLogProb();
    		if (this.useTopicProp) logprob += globalTopic.topicPropotional();

			for (Document doc: docSet) {
				TopicModel localTopic = doc.getTopic(); //docSet.getLocalTopic(d);
				//logprob += localTopic.getLogProb();
	    		//for (int topic_id : localTopic.getIds()) {
	    			//logprob += languageModel.logDCM(localTopic.getWordVector(topic_id), W, dirPriorOfLocalLM);
	    			//logprob += localTopic.getLogProb(topic_id);
	    		//}
				if (localTopic.size() > 0) {
					logprob += localTopic.getLogProb();
		    		logprob += doc.logPriorOfTopicType();
		    		if (this.useTopicProp) logprob += localTopic.topicPropotional();
				}
			}
			
    	}
    	
    	return logprob;
    }
    
    
    public double logprob(TopicModel topicseg, int l1, int l2, double prior) {
//    	int W = topicseg.nVocab();
//    	SparseWordVector wv1 = topicseg.getWordVector(l1);
//    	SparseWordVector wv2 = topicseg.getWordVector(l2);
    	
       	//double logprob1 = languageModel.logDCM(wv1, W, prior);
       	//double logprob2 = languageModel.logDCM(wv2, W, prior);
    	double logprob1 = topicseg.recomputeLogProb(l1);
    	double logprob2 = topicseg.recomputeLogProb(l2);
       	
       	topicseg.storeLogProb(l1, logprob1);
       	topicseg.storeLogProb(l2, logprob2);
       	
        return logprob1 + logprob2;
    }

    private int sampleSegmentByUniform(DocumentSet docSet) {
		int rd = Uniform.staticNextIntFromTo(0, docSet.size()-1);
		int rv = Uniform.staticNextIntFromTo(0, docSet.get(rd).size()-1);
		return docSet.get(rd).getTopicLabel()[rv];
    }
    
	int maxShiftMoves = 20;
	double[] shiftProposal;
	double[] cumShiftProposal;
	int maxSplitMoves = 20;
	double[] splitProposal;
	double[] cumSplitProposal;

    private void initializeDistanceProposal() {
	    // shift move proposal
        shiftProposal = new double[maxShiftMoves*2+1];  
        double normalizing = 0.0;
        for (int i = 1; i <= maxShiftMoves; i++){
            shiftProposal[maxShiftMoves + i] = Math.pow(i,-1.0/Math.sqrt(maxShiftMoves));
            shiftProposal[maxShiftMoves - i] = Math.pow(i,-1.0/Math.sqrt(maxShiftMoves));
            normalizing += shiftProposal[maxShiftMoves + i] + shiftProposal[maxShiftMoves - i];
        }
        cumShiftProposal = new double[maxShiftMoves*2+2];
        for (int i = 0; i <shiftProposal.length; i++) { 
        	shiftProposal[i] /= normalizing;
        	cumShiftProposal[i+1] += cumShiftProposal[i] + shiftProposal[i]; 
        }
        
	    // split move proposal
        splitProposal = new double[maxSplitMoves*2+1];  
        normalizing = 0.0;
        for (int i = 1; i <= maxSplitMoves; i++){
            splitProposal[maxSplitMoves + i] = Math.pow(i,-1.0/Math.sqrt(maxSplitMoves));
            splitProposal[maxSplitMoves - i] = Math.pow(i,-1.0/Math.sqrt(maxSplitMoves));
            normalizing += splitProposal[maxSplitMoves + i] + splitProposal[maxSplitMoves - i];
        }
        cumSplitProposal = new double[maxSplitMoves*2+2];
        for (int i = 0; i < splitProposal.length; i++) { 
        	splitProposal[i] /= normalizing;
        	cumSplitProposal[i+1] += cumSplitProposal[i] + splitProposal[i]; 
        }
        
    }
    
    public double[] sampleShiftMoveTransform(Document doc, int idx) {
    	int curSegpt  = doc.getSegment(idx);
        int prevSegpt = (idx > 0) ? doc.getSegment(idx-1) : 0;        
        int nextSegpt = doc.getSegment(idx+1);
    	
        int startIdx = Math.max( 0, (prevSegpt - curSegpt) + maxShiftMoves ) + 1;
        int endIdx = Math.min( maxShiftMoves * 2, (nextSegpt - curSegpt) + maxShiftMoves ) - 1;
        double norm = cumShiftProposal[endIdx+1] - cumShiftProposal[startIdx];
        
		double r = Uniform.staticNextDouble() * norm;
		int sampleIdx = 0;
		for (sampleIdx = startIdx; sampleIdx < endIdx; sampleIdx++) {
			if (r < cumShiftProposal[sampleIdx+1] - cumShiftProposal[startIdx])
				break;
		}
		
        int amount = sampleIdx - maxShiftMoves;
        double prob = (norm > 0) ? shiftProposal[sampleIdx] / norm : 0.0;
		
        startIdx = Math.max(0, (prevSegpt - curSegpt + amount) + maxShiftMoves) + 1;
        endIdx = Math.min(maxShiftMoves*2, (nextSegpt - curSegpt + amount) + maxShiftMoves) - 1;
        double invNorm = cumShiftProposal[endIdx+1] - cumShiftProposal[startIdx];
        int invZ = maxShiftMoves - amount;
        double invProb = (invNorm > 0) ? shiftProposal[invZ] / invNorm : 0.0;
		
        return new double[] { amount, invProb / prob };
    }
    
    public double[] sampleSplitMoveTransform(Document doc, int idx) {
    	int startSegpt = doc.getSegmentStartPos(idx);
    	int endSegpt  = doc.getSegment(idx);
    	int middleSegpt = startSegpt + (endSegpt - startSegpt) / 2;
    	
        int startIdx = Math.max( 0, (startSegpt - middleSegpt) + maxSplitMoves ) + 1;
        int endIdx = Math.min( maxSplitMoves * 2, (endSegpt - middleSegpt) + maxSplitMoves ) - 1;
        double norm = cumSplitProposal[endIdx+1] - cumSplitProposal[startIdx];
        
		double r = Uniform.staticNextDouble() * norm;
		int sampleIdx = 0;
		for (sampleIdx = startIdx; sampleIdx < endIdx; sampleIdx++) {
			if (r < cumSplitProposal[sampleIdx+1] - cumSplitProposal[startIdx])
				break;
		}
		
        int amount = sampleIdx - maxSplitMoves;
        double prob = (norm > 0) ? splitProposal[sampleIdx] / norm : 0.0;
		
        startIdx = Math.max( 0, (startSegpt - middleSegpt) + maxSplitMoves ) + 1;
        endIdx = Math.min( maxSplitMoves * 2, (endSegpt - middleSegpt) + maxSplitMoves ) - 1;
        double invNorm = cumSplitProposal[endIdx+1] - cumSplitProposal[startIdx];
        int invZ = maxSplitMoves - amount;
        double invProb = (invNorm > 0) ? splitProposal[invZ] / invNorm : 0.0;
        return new double[] { middleSegpt + amount, invProb / prob };
    }

	public void updateSegment(TopicModel topicModel, int oldTopicId, int newTopicId, Document doc, int start, int end) {
		for (int t = start; t < end; t++) {
			topicModel.update(doc.getSentence(t), oldTopicId, -1);
			topicModel.update(doc.getSentence(t), newTopicId, 1);
			doc.setTopicLabel(t, newTopicId);
		}
		doc.resetTopicLabel();
	}
	
	public void updateSegment(TopicModel topicModel1, TopicModel topicModel2, int oldTopicId, int newTopicId, Document doc, int start, int end, int type) {
		int label = (type == 1) ? (newTopicId * -1 - 1) : newTopicId;
			
		for (int t = start; t < end; t++) {
			topicModel1.update(doc.getSentence(t), oldTopicId, -1);
			topicModel2.update(doc.getSentence(t), newTopicId, 1);
			doc.setTopicLabel(t, label);
		}
		doc.resetTopicLabel();
	}

    ///===================================================================

	protected int[] uniformseg(int T, int K) {
		int[] topicLabels = new int[T];

        Arrays.fill(topicLabels, -1);
        int segstart = 0, segend = 0; 
        for (int k = 0; k < K; k++)
        {
        	double val = (double)T / K;
    		segstart = (int)Math.round(k * val); segend = (int)Math.round((k+1) * val);
        	for (int i = segstart; i < segend && i < T; i++)
        		topicLabels[i] = k;
        }
        for (int i = segend; i < T; i++)
        	topicLabels[i] = K -1;
        
		return topicLabels;
	}

}
