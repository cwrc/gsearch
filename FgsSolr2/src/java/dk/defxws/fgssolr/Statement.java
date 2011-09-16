//$Id$
/*
 * <p><b>License and Copyright: </b>The contents of this file is subject to the
 * same open source license as the Fedora Repository System at www.fedora-commons.org
 * Copyright &copy; 2006, 2007, 2008, 2009, 2010, 2011 by The Technical University of Denmark.
 * All rights reserved.</p>
 */
package dk.defxws.fgssolr;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.ListIterator;
import java.util.Locale;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryParser.MultiFieldQueryParser;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.FieldComparatorSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.Fragmenter;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Version;

import dk.defxws.fedoragsearch.server.errors.GenericSearchException;
import fedora.server.utilities.StreamUtility;

/**
 * queries the Solr index 
 * 
 * @author  gsp@dtv.dk
 * @version 
 */
public class Statement {
    
    private static final Logger logger = Logger.getLogger(Statement.class);

    private IndexSearcher searcher;
    
    public ResultSet executeQuery(
            String queryString, 
            int startRecord, 
            int maxResults,
            int snippetsMax,
            int fieldMaxLength,
            Analyzer analyzer, 
            String defaultQueryFields, 
            String indexPath, 
            String indexName, 
            String snippetBegin,
            String snippetEnd, 
            String sortFields)
    throws GenericSearchException {
    	ResultSet rs = null;
    	StringTokenizer defaultFieldNames = new StringTokenizer(defaultQueryFields);
    	int countFields = defaultFieldNames.countTokens();
    	String[] defaultFields = new String[countFields];
    	for (int i=0; i<countFields; i++) {
    		defaultFields[i] = defaultFieldNames.nextToken();
    	}
    	Query query = null;
    	if (defaultFields.length == 1) {
    		try {
    			query = (new QueryParser(Version.LUCENE_33, defaultFields[0], analyzer)).parse(queryString);
    		} catch (ParseException e) {
    			throw new GenericSearchException(e.toString());
    		}
    	}
    	else {
    		try {
    			query = (new MultiFieldQueryParser(Version.LUCENE_33, defaultFields, analyzer)).parse(queryString);
    		} catch (ParseException e) {
    			throw new GenericSearchException(e.toString());
    		}
    	}
    	IndexReader ir = null;
    	try {
			Directory dir = new SimpleFSDirectory(new File(indexPath));
    		ir = IndexReader.open(dir, true);
    		query.rewrite(ir);
    	} catch (CorruptIndexException e) {
    		throw new GenericSearchException(e.toString());
    	} catch (IOException e) {
    		throw new GenericSearchException(e.toString());
    	} finally {
    		if (ir!=null) {
        		try {
    				ir.close();
    			} catch (IOException e) {
    			}
    		}
    	}
    	try {
			Directory dir = new SimpleFSDirectory(new File(indexPath));
    		searcher = new IndexSearcher(dir, true);
    	} catch (CorruptIndexException e) {
    		throw new GenericSearchException(e.toString());
    	} catch (IOException e) {
    		throw new GenericSearchException(e.toString());
    	}
    	int start = Integer.parseInt(Integer.toString(startRecord));
    	TopDocs hits = getHits(query, start+maxResults-1, sortFields);
    	ScoreDoc[] docs = hits.scoreDocs;
    	int end = Math.min(hits.totalHits, start + maxResults - 1);
    	StringBuffer resultXml = new StringBuffer();
    	resultXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    	String queryStringEncoded = null;
    	try {
    		queryStringEncoded = URLEncoder.encode(queryString, "UTF-8");
    	} catch (UnsupportedEncodingException e) {
    		errorExit(e.toString());
    	}
    	resultXml.append("<solrsearch "+
    			"   xmlns:dc=\"http://purl.org/dc/elements/1.1/"+
    			"\" query=\""+queryStringEncoded+
    			"\" indexName=\""+indexName+
    			"\" sortFields=\""+sortFields+
    			"\" hitPageStart=\""+startRecord+
    			"\" hitPageSize=\""+maxResults+
    			"\" hitTotal=\""+hits.totalHits+"\">");
		ScoreDoc hit = null;
		Document doc = null;
		String hitsScore = null;
    	for (int i = start; i <= end; i++)
    	{
    		try {
    			hit = docs[i-1];
    			doc = searcher.doc(hit.doc);
    			hitsScore = ""+hit.score;
    		} catch (CorruptIndexException e) {
    			errorExit(e.toString());
    		} catch (IOException e) {
    			errorExit(e.toString());
    		}
    		resultXml.append("<hit no=\""+i+ "\" score=\""+hitsScore+"\">");
    		for (ListIterator li = doc.getFields().listIterator(); li.hasNext(); ) {
    			Field f = (Field)li.next();
    			resultXml.append("<field name=\""+f.name()+"\"");
    			String snippets = null;
    			if (snippetsMax > 0) {
    				SimpleHTMLFormatter formatter = new SimpleHTMLFormatter("!!!SNIPPETBEGIN", "!!!SNIPPETEND");
    				QueryScorer scorer = new QueryScorer(query, f.name());
    				Highlighter highlighter = new Highlighter(formatter, scorer);
    				Fragmenter fragmenter = new SimpleFragmenter(fieldMaxLength);
    				highlighter.setTextFragmenter(fragmenter);
    				TokenStream tokenStream = analyzer.tokenStream( f.name(), new StringReader(f.stringValue()));
    				try {
    					snippets = highlighter.getBestFragments(tokenStream, f.stringValue(), snippetsMax, " ... ");
    				} catch (Exception e) { // all Exceptions to be caught, not just IOException 
    					errorExit(e.toString());
    				}
    				snippets = checkTruncatedWords(snippets, " ... ");
    				snippets = StreamUtility.enc(snippets);
    				snippets = snippets.replaceAll("!!!SNIPPETBEGIN", snippetBegin);
    				snippets = snippets.replaceAll("!!!SNIPPETEND", snippetEnd);
    				if (snippets!=null && !snippets.equals("")) {
    					resultXml.append(" snippet=\"yes\">"+snippets);
    				}
    			}
    			if (snippets==null || snippets.equals(""))
    				if (fieldMaxLength > 0 && f.stringValue().length() > fieldMaxLength) {
    					String snippet = f.stringValue().substring(0, fieldMaxLength);
    					int iamp = snippet.lastIndexOf("&");
    					if (iamp>-1 && iamp>fieldMaxLength-8)
    						snippet = snippet.substring(0, iamp);
    					resultXml.append(">"+StreamUtility.enc(snippet)+" ... ");
    				} else
    					resultXml.append(">"+StreamUtility.enc(f.stringValue()));
    			resultXml.append("</field>");
    		}
    		resultXml.append("</hit>");
    	}
    	resultXml.append("</solrsearch>");
    	rs = new ResultSet(resultXml);
    	if (searcher!=null) {
    		try {
    			searcher.close();
    		} catch (IOException e) {
    		}
    	}
    	return rs;
    }

    private void errorExit(String message) throws GenericSearchException {
    	if (searcher!=null) {
    		try {
    			searcher.close();
    		} catch (IOException e) {
    		}
    	}
    	throw new GenericSearchException(message);
    }

//  sortFields      ::= [sortField[';'sortField]*]
//  sortField       ::= sortFieldName[','(sortType | locale | comparatorClass)[','reverse]]]]
//  sortFieldName   ::= #the name of an index field, which is UN_TOKENIZED and contains a single term per document
//  sortType        ::= 'AUTO' ~ 'SCORE' (default) | 'DOC' | 'INT' | 'SHORT' | 'LONG' | 'FLOAT' | 'DOUBLE' | 'BYTE' | 'STRING' | 'STRING_VAL'
//  locale          ::= language['-'country['-'variant]]
//  comparatorClass ::= package-path'.'className['('param['-'param]*')']
//  reverse         ::= 'ASC' ~ 'false' (~ascending (default)) | 'DESC' ~ 'true' ~ 'reverse' (~descending)
    private TopDocs getHits(Query query, int numHits, String sortFields) throws GenericSearchException {
    	TopDocs hits = null;
    	IndexReader ireader = searcher.getIndexReader();
    	Collection fieldNames = ireader.getFieldNames(IndexReader.FieldOption.ALL);
    	String sortFieldsString = sortFields;
    	if (sortFields == null) sortFieldsString = "";
    	StringTokenizer st = new StringTokenizer(sortFieldsString, ";");
    	SortField[] sortFieldArray = new SortField[st.countTokens()];
    	int i = 0;
    	while (st.hasMoreTokens()) {
    		SortField sortField = null;
			int sortType = -1;
    		String sortFieldString = st.nextToken().trim();
    		if (sortFieldString.length()==0)
    			errorExit("getHits sortFields='"+sortFields+"' : empty sortField string");
    		StringTokenizer stf = new StringTokenizer(sortFieldString, ",");
    		if (!stf.hasMoreTokens())
    			errorExit("getHits sortFields='"+sortFields+"' : empty sortFieldName string in '" + sortFieldString + "'");
    		String sortFieldName = stf.nextToken().trim();
    		if (sortFieldName.length()==0)
    			errorExit("getHits sortFields='"+sortFields+"' : empty sortFieldName string in '" + sortFieldString + "'");
    		if (!fieldNames.contains(sortFieldName))
    			errorExit("getHits sortFields='"+sortFields+"' : sortFieldName '" + sortFieldName + "' not found as index field name");
    		if (!stf.hasMoreTokens()) {
    			sortType = SortField.SCORE;
				sortField = new SortField(sortFieldName, sortType);
    		} else {
    			String sortTypeOrLocaleOrCompString = stf.nextToken().trim();
    			if (sortTypeOrLocaleOrCompString.length()==0)
    				errorExit("getHits sortFields='"+sortFields+"' : empty sortType or locale or comparatorClass string in '" + sortFieldString + "'");
    			if (sortTypeOrLocaleOrCompString.indexOf(".")>=0) {
    				String compString = sortTypeOrLocaleOrCompString;
    				String paramString = "";
    				Object[] params = new Object[] {};
    				if (sortTypeOrLocaleOrCompString.indexOf("(")>=0) {
    					int p = compString.indexOf("(");
    					int q = compString.indexOf(")");
    					if (p<3 || q<p+1)
    						errorExit("getHits sortFields='"+sortFields+"' : comparatorClass parameters malformed in '" + compString + "'.");
    					paramString = compString.substring(p+1, q);
    					compString = compString.substring(0, p);
    					StringTokenizer stp = new StringTokenizer(paramString, "-");
    					params = new Object[stp.countTokens()];
    					int ip = 0;
    					while (stp.hasMoreTokens()) {
    						params[ip++] = stp.nextToken().trim();
    					}
    				}
    				FieldComparatorSource scs = null;
    				Class comparatorClass = null;
    				try {
    					comparatorClass = Class.forName(compString);
    				} catch (ClassNotFoundException e) {
    					errorExit("getHits sortFields='"+sortFields+"' : comparatorClass '" + compString + "'"
    							+ ": class not found:\n"+e.toString());
    				}
    				Constructor[] constructors = comparatorClass.getConstructors();
    				StringBuffer errorMessage = new StringBuffer();
    				for (int j=0; j<constructors.length; j++) {
    					Constructor cj = constructors[j];
    					try {
    						scs = (FieldComparatorSource) cj.newInstance(params);
    						if (logger.isDebugEnabled())
    							logger.debug("getHits sortFields='"+sortFields+"' : comparatorClass '" 
    									+ compString + "'"
    									+ ": constructor["+j+"]='"+cj.toGenericString()+"'");
    						break;
    					} catch (IllegalArgumentException e) {
    						errorMessage.append("\nconstructor["+j+"]='"+cj.toGenericString()+"'"+"\n"+e.toString()+" ");
    					} catch (InstantiationException e) {
    						errorMessage.append("\nconstructor["+j+"]='"+cj.toGenericString()+"'"+"\n"+e.toString()+" ");
    					} catch (IllegalAccessException e) {
    						errorMessage.append("\nconstructor["+j+"]='"+cj.toGenericString()+"'"+"\n"+e.toString()+" ");
    					} catch (InvocationTargetException e) {
    						errorMessage.append("\nconstructor["+j+"]='"+cj.toGenericString()+"'"+"\n"+e.toString()+" ");
    					}
    				}
    				if (scs==null) {
    					errorExit("getHits sortFields='"+sortFields+"' : comparatorClass '" + compString + "'"
    							+ ": no constructor applied:\n"+errorMessage.toString());
    				}
    				if (!stf.hasMoreTokens()) {
    					sortField = new SortField(sortFieldName, scs);
    				} else {
    					String reverseString = stf.nextToken().trim();
    					if (reverseString.length()==0)
    						errorExit("getHits sortFields='"+sortFields+"' : empty reverse string in '" + sortFieldString + "'");
    					boolean reverse = false;
    					if ("true".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("reverse".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("false".equalsIgnoreCase(reverseString)) reverse = false;
    					else if ("DESC".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("ASC".equalsIgnoreCase(reverseString)) reverse = false;
    					else
    						errorExit("getHits sortFields='"+sortFields+"' : unknown reverse string '" + reverseString + "' in '" + sortFieldString + "'");
    					sortField = new SortField(sortFieldName, scs, reverse);
    				}
    			} else {
    				String sortTypeOrLocaleString = sortTypeOrLocaleOrCompString;
    				Locale locale = null;
    				if ("AUTO".equals(sortTypeOrLocaleString)) sortType = SortField.SCORE;
    				else if ("SCORE".equals(sortTypeOrLocaleString)) sortType = SortField.SCORE;
    				else if ("DOC".equals(sortTypeOrLocaleString)) sortType = SortField.DOC;
    				else if ("INT".equals(sortTypeOrLocaleString)) sortType = SortField.INT;
    				else if ("SHORT".equals(sortTypeOrLocaleString)) sortType = SortField.SHORT;
    				else if ("LONG".equals(sortTypeOrLocaleString)) sortType = SortField.LONG;
    				else if ("FLOAT".equals(sortTypeOrLocaleString)) sortType = SortField.FLOAT;
    				else if ("DOUBLE".equals(sortTypeOrLocaleString)) sortType = SortField.DOUBLE;
    				else if ("BYTE".equals(sortTypeOrLocaleString)) sortType = SortField.BYTE;
    				else if ("STRING".equals(sortTypeOrLocaleString)) sortType = SortField.STRING;
    				else if ("STRING_VAL".equals(sortTypeOrLocaleString)) sortType = SortField.STRING_VAL;
    				else if (((sortTypeOrLocaleString.substring(0, 1)).compareTo("A") >= 0) && ((sortTypeOrLocaleString.substring(0, 1)).compareTo("Z") <= 0)) {
    					errorExit("getHits sortFields='"+sortFields+"' : unknown sortType string '" + sortTypeOrLocaleString + "' in '" + sortFieldString + "'");
    				}
    				else {
    					StringTokenizer stfl = new StringTokenizer(sortTypeOrLocaleString, "-");
    					if (stfl.countTokens()>3)
    						errorExit("getHits sortFields='"+sortFields+"' : unknown locale string '" + sortTypeOrLocaleString + "' in '" + sortFieldString + "'");
    					String language = stfl.nextToken().trim();
    					if (language.length()==0)
    						errorExit("getHits sortFields='"+sortFields+"' : empty language string in '" + sortFieldString + "'");
    					if (language.length()>2)
    						errorExit("getHits sortFields='"+sortFields+"' : unknown language string '" + language + "' in '" + sortFieldString + "'");
    					if (!stfl.hasMoreTokens()) {
    						locale = new Locale(language);
    					} else {
    						String country = stfl.nextToken().trim();
    						if (country.length()==0)
    							errorExit("getHits sortFields='"+sortFields+"' : empty country string in '" + sortFieldString + "'");
    						if (country.length()>3)
    							errorExit("getHits sortFields='"+sortFields+"' : unknown country string '" + country + "' in '" + sortFieldString + "'");
    						if (!stfl.hasMoreTokens()) {
    							locale = new Locale(language, country);
    						} else {
    							String variant = stfl.nextToken().trim();
    							if (variant.length()==0)
    								errorExit("getHits sortFields='"+sortFields+"' : empty variant string in '" + sortFieldString + "'");
    							locale = new Locale(language, country, variant);
    						}
    					}
    				}
    				if (!stf.hasMoreTokens()) {
    					if (sortType >= 0)
    						sortField = new SortField(sortFieldName, sortType);
    					else
    						sortField = new SortField(sortFieldName, locale);
    				} else {
    					String reverseString = stf.nextToken().trim();
    					if (reverseString.length()==0)
    						errorExit("getHits sortFields='"+sortFields+"' : empty reverse string in '" + sortFieldString + "'");
    					boolean reverse = false;
    					if ("true".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("reverse".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("false".equalsIgnoreCase(reverseString)) reverse = false;
    					else if ("DESC".equalsIgnoreCase(reverseString)) reverse = true;
    					else if ("ASC".equalsIgnoreCase(reverseString)) reverse = false;
    					else
    						throw new GenericSearchException("getHits sortFields='"+sortFields+"' : unknown reverse string '" + reverseString + "' in '" + sortFieldString + "'");
    					if (sortType == SortField.SCORE) reverse = !reverse;
    					if (sortType >= 0)
    						sortField = new SortField(sortFieldName, sortType, reverse);
    					else
    						sortField = new SortField(sortFieldName, locale, reverse);
    				}
    			}
    		}
    		sortFieldArray[i++] = sortField;
    	}
    	TopDocsCollector collector = null;
    	if (sortFieldArray.length == 0) {
    		collector = TopScoreDocCollector.create(numHits, true);
    	} else {
    		try {
    	    	Sort sort = new Sort(sortFieldArray);
    	    	collector = TopFieldCollector.create( sort, numHits, true, true, true, true);
    		} catch (Exception e) {
    			errorExit("getHits TopFieldCollector sortFields='"+sortFields+"' : "+e.toString());
    		} 
    	}
    	try {
    		searcher.search(query, collector);
    	} catch (IOException e) {
    		errorExit("getHits search sortFields='"+sortFields+"' : "+e.toString());
    	} catch (RuntimeException e) {
    		errorExit("getHits search RuntimeException sortFields='"+sortFields+"' : "+e.toString());
    	}
    	hits = collector.topDocs();
    	return hits;
    }

    //	contributed by Leire Urcelay
    private String checkTruncatedWords(String snippets, String separator) {
    	String transformedSnippets = "";

    	if (snippets!=null && !snippets.equals("")) {
    		int separatorIndex = snippets.indexOf(separator);
    		while (separatorIndex > -1 ) {
    			transformedSnippets = transformedSnippets.concat(removeLastWordIfNeeded(snippets.substring(0, separatorIndex)));
    			transformedSnippets = transformedSnippets.concat(separator);
    			snippets = snippets.substring(separatorIndex + separator.length());    			
    			separatorIndex = snippets.indexOf(separator);
    		}
    		//add last node
    		snippets = removeLastWordIfNeeded(snippets.substring(0, snippets.length()));
    		transformedSnippets = transformedSnippets.concat(snippets);
    	}
    	else {
    		transformedSnippets = snippets;
    	}
    	return transformedSnippets;
    }

    private String removeLastWordIfNeeded(String snippetsFragment) {
    	int lastWordIndex = snippetsFragment.lastIndexOf(" ");
    	if ((lastWordIndex > -1) && (lastWordIndex + 1  <= snippetsFragment.length())) {
    		String lastWord = snippetsFragment.substring(lastWordIndex + 1, snippetsFragment.length());
    		if ((lastWord.startsWith("&")) && (!lastWord.endsWith(";"))) {
    			snippetsFragment = snippetsFragment.substring(0, lastWordIndex);    			
    		}	
    	}
    	return snippetsFragment;
    }
    
    void close() throws GenericSearchException {
    }
}
