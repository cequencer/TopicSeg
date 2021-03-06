/*
 * Copyright (C) 2010 Cluster of Excellence, Univ. of Saarland
 * Minwoo Jeong (minwoo.j@gmail.com) is a main developer.
 * This file is part of "TopicSeg" package.
 * This software is provided under the terms of LGPL.
 */

package topicseg.document;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;

import com.aliasi.util.Arrays;

import topicseg.document.SparseWordVector.WordId;
import topicseg.model.*;

/**
 * unisaar.topicseg.document::Document.java
 *
 * @author minwoo
 */
public class Document implements Serializable {

	private static final long serialVersionUID = 1L;
	private transient Logger logger = Logger.getLogger(Document.class);
	
	protected Alphabet dict;
	protected ArrayList<SparseWordVector> wordMatrix; 
	//protected ArrayList<SparseWordVector> cum_word_matrix;
	protected TopicModel topic;
	
	/**
	 * Construct an object with internal dictionary 
	 */
	public Document() {
		this(new Alphabet());
	}
	
	/**
	 * Construct an object with external dictionary
	 * @param dict
	 */
	public Document(Alphabet dict) {
		this.dict = dict;
		wordMatrix = new ArrayList<SparseWordVector>();
		boundary = new ArrayList<SegmentBoundary>();
		topic_pool = new HashSet<Integer>();
		//cum_word_matrix = new ArrayList<SparseWordVector>();
		topic = new TopicModel();
	}
	
	/**
	 * Add one sentence to document
	 * @param sent
	 */
	public void add(String sent) {
		SparseWordVector termVec = new SparseWordVector();
		
		StringTokenizer tokens = new StringTokenizer(sent, " ");
		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken();
			int id = dict.lookup(token);
			termVec.add(id, 1);
		}
		wordMatrix.add(termVec);
		
		/*
		if (cum_word_matrix.size() == 0)
			cum_word_matrix.add(new SparseWordVector());

		SparseWordVector cumTermVec = new SparseWordVector();
		cumTermVec.add(cum_word_matrix.get(cum_word_matrix.size()-1));
		cumTermVec.add(termVec);
		cum_word_matrix.add(cumTermVec);
		*/
	}
	
	public void tfidf() {
		
		double D = (double)wordMatrix.size();
		
		for (SparseWordVector termVec : wordMatrix) {
			int[] ids = termVec.getIds();
			double[] vals = termVec.getVals();
			double total = termVec.sum();
			
			double[] w = new double[ids.length];
			for (int i = 0; i < vals.length; i++) {
				w[i] = vals[i] / total;
				int count = 0;
				for (SparseWordVector vec : wordMatrix)
					if (vec.contains(ids[i])) count++;
				double idf = Math.log(D / (1 + (double)count));
				w[i] *= idf;
				//System.out.println(dict.getObject(ids[i]) + " " + vals[i] + " " + w[i]);
				if (w[i] < 0.1) 
					termVec.add(ids[i], -vals[i]);
			}
			termVec.trim();
		}
		
	}
	
	/**
	 * Get one term vector that represents a sentence
	 * @param index
	 * @return term vector
	 */
	public SparseWordVector getSentence(int index) {
		return wordMatrix.get(index);
	}
	
	/*
	public SparseWordVector getCumVector(int index) {
		return cum_word_matrix.get(index);
	}
	*/
	
//	// slow
	public SparseWordVector getMergedWordVector(int start, int end) {
		SparseWordVector ret = new SparseWordVector();
		for (int i = start; i < end; i++)
			ret.add(wordMatrix.get(i));
		return ret;
	}
	
	public Alphabet getAlphabet() { 
		return dict; 
	}
	
	public int size() { 
		return wordMatrix.size(); 
	}
	
	public int nWord() {
		int v = 0;
		for (SparseWordVector wordVec : wordMatrix) 
			v += wordVec.size();
		return v;
	}
	
	//------------------------------------------------
	// Segment information
	// TODO: moves this part outside class?  
	
	ArrayList<SegmentBoundary> boundary;
	int[] hidden_topic;
	int[] seg_index;
	Set<Integer> topic_pool;

	public int nSegment() { 
		return boundary.size(); 
	}
	
	public Set<Integer> getPool() { 
		return topic_pool; 
	}
	
	public void insertSegment(int idx, int id) {
		SegmentBoundary old_seg = boundary.get(seg_index[idx]);
		SegmentBoundary new_seg = new SegmentBoundary();
		
		new_seg.start = idx; new_seg.end = old_seg.end; new_seg.id = id;
		old_seg.end = idx;
		boundary.add(seg_index[idx]+1, new_seg);
	}
	
	public void setTopicLabel(int idx, int label) { 
		hidden_topic[idx] = label; 
	}
	
	public void setTopicLabel(int[] topicLabels) {
		this.hidden_topic = topicLabels;
		
		boundary = new ArrayList<SegmentBoundary>();
        int pre_label = topicLabels[0];
        int pre_end = 0;
        for (int i = 1; i < topicLabels.length; i++)
        {
    		if (pre_label != hidden_topic[i]) { 
            	boundary.add(new SegmentBoundary(pre_end, i, pre_label));
            	pre_end = i;
    		}
    		pre_label = hidden_topic[i];
        }
    	boundary.add(new SegmentBoundary(pre_end, hidden_topic.length, pre_label));
	}
	
	public ArrayList<SegmentBoundary> getSegmentBoundary(int[] topicLabels) {
		ArrayList<SegmentBoundary> instBoundary = new ArrayList<SegmentBoundary>();
        int pre_label = topicLabels[0];
        int pre_end = 0;
        for (int i = 1; i < topicLabels.length; i++) {
    		if (pre_label != topicLabels[i]) { 
            	instBoundary.add(new SegmentBoundary(pre_end, i, pre_label));
            	pre_end = i;
    		}
    		pre_label = topicLabels[i];
        }
    	instBoundary.add(new SegmentBoundary(pre_end, topicLabels.length, pre_label));
    	
    	return instBoundary;
	}

	public int getLabel(int idx) {
		return boundary.get(idx).id;
	}

	public int getSegment(int idx) {
		return boundary.get(idx).end;
	}
	
	public int getSegmentStartPos(int idx) {
		return boundary.get(idx).start;
	}
	
	public int whereTopic(int id) {
		for (int idx = 0; idx < boundary.size(); idx++)
			if (boundary.get(idx).id == id)
				return idx;
		return -1;
	}
	
	public void resetTopicLabel() {
		setTopicLabel(hidden_topic);
	}
	
	public class SegmentBoundary {
		public int start;
		public int end;
		public int id;
		
		public SegmentBoundary() {
			start = -1; end = -1;
			id = -1; 
		}
		
		public SegmentBoundary(int start, int end, int id) {
			this.start = start; 
			this.end = end;
			this.id = id;
		}
	}
	
	TopicTypeModel docPrior;
	public void initDocPrior(double alpha, double beta, double gamma) {
		docPrior = new TopicTypeModel(alpha, beta);
	}
	
	public double logPriorOfTopicType() {
		int nGlobal = 0, nLocal = 0;
		for (int i = 0; i < hidden_topic.length; i++) {
			if (hidden_topic[i] >= 0) nGlobal++;
			else nLocal++;
		}
		
		return docPrior.logProb(nGlobal, nLocal);
	}
	
	public TopicTypeModel getDocPrior() {
		return docPrior;
	}
	
	//----------------------------------------------------------
	/*
	public void resetTopicLabel() {
		segpts = new ArrayList<Integer>();
		labels = new ArrayList<Integer>();
        int pre_label = hidden_topic[0];
        segpts.add(0);  labels.add(pre_label);
        for (int i = 1; i < hidden_topic.length; i++)
        {
        	int label = hidden_topic[i];
    		if (pre_label != label) {
	            segpts.add(i); 
	            labels.add(pre_label);
    		}
    		pre_label = hidden_topic[i];
        }
        segpts.add(hidden_topic.length); 
        labels.add(pre_label);
	}
	*/
	
	public int[] getTopicLabel() {
		return hidden_topic;
	}
	
	public TopicModel getTopic() {
		return topic;
	}
	
	// document information
	protected int docId; // doc id
	protected String title;	// doc title
	protected String description; // short description of doc
	protected String author; // author or provider
	protected String url; // url
	protected String extension; // file extension in dataset

	public void setInfo(String title, String desc, String author, String url, String ext) {
		this.title = title;
		this.description = desc;
		this.author = author;
		this.url = url;
		this.extension = ext;
	}
	
	public void setId(int id) {
		this.docId = id;
	}
	
	public int getId() {
		return docId;
	}

	public String[] getInfo() {
		return new String[] {title, description, author, url, extension};
	}
	
	public String toString(int index) {
		String ret = "";
		SparseWordVector wordVec = wordMatrix.get(index);
		
		for (int i = 0; i < wordVec.size(); i++) {
			WordId w = wordVec.getWordId(i);
			ret += dict.getObject(w.getId()) + ":" + w.getVal() + " ";
		}
		
		return ret.trim();
	}

	// 불필요한 것들............
	//protected double[][] word_array; // for efficiency, but it wastes the memory if the word vector is sparse.
	
//	/**
//	 * Make dense term matrix and return it 
//	 * @return dense term matrix
//	 */
//	public double[][] toArray() {
//		int nSent = wordMatrix.size();
//		int nVocab = dict.size();
//		
//		word_array = new double[nSent][];
//		for (int i = 0; i < wordMatrix.size(); i++ ) 
//			word_array[i] = wordMatrix.get(i).toArray(nVocab);
//		
//		return word_array;
//	}
//
//	public double[][] getArray() { 
//		return word_array; 
//	}

}
