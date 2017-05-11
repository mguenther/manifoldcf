/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.agents.transformation.tikaservice;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.agents.system.Logging;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaMetadataKeys;
import org.apache.tika.parser.html.BoilerpipeContentHandler;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import de.l3s.boilerpipe.BoilerpipeExtractor;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * This connector works as a transformation connector, but does nothing other
 * than logging.
 *
 */
public class TikaExtractor extends org.apache.manifoldcf.agents.transformation.BaseTransformationConnector {
  public static final String _rcsid = "@(#)$Id$";

  private static final String EDIT_CONFIGURATION_JS = "editConfiguration.js";
  private static final String EDIT_CONFIGURATION_SERVER_HTML = "editConfiguration_Server.html";
  private static final String VIEW_CONFIGURATION_HTML = "viewConfiguration.html";
  private static final String EDIT_SPECIFICATION_JS = "editSpecification.js";
  private static final String EDIT_SPECIFICATION_FIELDMAPPING_HTML = "editSpecification_FieldMapping.html";
  private static final String EDIT_SPECIFICATION_EXCEPTIONS_HTML = "editSpecification_Exceptions.html";
  private static final String VIEW_SPECIFICATION_HTML = "viewSpecification.html";

  protected static final String ACTIVITY_EXTRACT = "extract";

  protected static final String[] activitiesList = new String[] { ACTIVITY_EXTRACT };
  protected final static long sessionExpirationInterval = 300000L;
  
  /** We handle up to 64K in memory; after that we go to disk. */
  protected static final long inMemoryMaximumFile = 65536;

  // Raw parameters
  
  /** Tika host name */
  private String tikaHostname = null;
  
  /** Tika port */
  private String tikaPortString = null;

  // Computed parameters

  /** Session timeout */
  private long sessionTimeout = -1L;
  
  /** Tika port */
  private int tikaPort = -1;

  /** Connection manager */
  private HttpClientConnectionManager connectionManager = null;
  
  /** Httpclient instance */
  private HttpClient httpClient = null;

  /** HttpHost */
  private HttpHost tikaHost = null;
  
  // Static data
  
  /** Metadata URI */
  protected final static URI metaURI;
  /** Content URI */
  protected final static URI contentURI;
  
  static {
    try {
      metaURI = new URI("/meta");
      contentURI = new URI("/tika");
    } catch (URISyntaxException e) {
      throw new RuntimeException(e.getMessage());
    }
  }
  
s  /** Connect.
  *@param configParameters is the set of configuration parameters, which
  * in this case describe the root directory.
  */
  @Override
  public void connect(ConfigParams configParameters)
  {
    super.connect(configParameters);
    tikaHostname = configParameters.getParameter(TikaConfig.PARAM_TIKAHOSTNAME);
    tikaPortString = configParameters.getParameter(TikaConfig.PARAM_TIKAPORT);
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    expireSession();

    super.disconnect();
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  @Override
  public void poll()
    throws ManifoldCFException
  {
    if (System.currentTimeMillis() >= sessionTimeout)
    {
      expireSession();
    }
    if (connectionManager != null)
      connectionManager.closeIdleConnections(60000L,TimeUnit.MILLISECONDS);
  }

  /** This method is called to assess whether to count this connector instance should
  * actually be counted as being connected.
  *@return true if the connector instance is actually connected.
  */
  @Override
  public boolean isConnected()
  {
    return sessionTimeout != -1L;
  }

  /** Set up a session */
  protected void getSession()
    throws ManifoldCFException
  {
    if (sessionTimeout == -1L)
    {
      this.tikaPort = Integer.parseInt(tikaPortString);

      final int connectionTimeout = 60000;
      final int socketTimeout = 900000;

      final PoolingHttpClientConnectionManager poolingConnectionManager = new PoolingHttpClientConnectionManager(RegistryBuilder.<ConnectionSocketFactory>create()
        .register("http", PlainConnectionSocketFactory.getSocketFactory())
        .register("https", myFactory)
        .build());
      poolingConnectionManager.setDefaultMaxPerRoute(1);
      poolingConnectionManager.setValidateAfterInactivity(2000);
      poolingConnectionManager.setDefaultSocketConfig(SocketConfig.custom()
        .setTcpNoDelay(true)
        .setSoTimeout(socketTimeout)
        .build());

      this.connectionManager = poolingConnectionManager;
      
      final RequestConfig.Builder requestBuilder = RequestConfig.custom()
          .setCircularRedirectsAllowed(true)
          .setSocketTimeout(socketTimeout)
          .setExpectContinueEnabled(false)
          .setConnectTimeout(connectionTimeout)
          .setConnectionRequestTimeout(socketTimeout);

      final HttpClientBuilder builder = HttpClients.custom()
        .setConnectionManager(connectionManager)
        .disableAutomaticRetries()
        .setDefaultRequestConfig(requestBuilder.build());
      builder.setRequestExecutor(new HttpRequestExecutor(socketTimeout))
        .setRedirectStrategy(new DefaultRedirectStrategy());
      this.httpClient = builder.build();

      this.tikaHost = new HttpHost(tikaHostname, tikaPort);

    }
    sessionTimeout = System.currentTimeMillis() + sessionExpirationInterval;
  }

  /** Expire the current session */
  protected void expireSession()
    throws ManifoldCFException
  {
    tikaHostname = null;
    tikaPortString = null;
    tikaPort = -1;
    httpClient = null;
    tikaHost = null;
    if (connectionManager != null)
      connectionManager.shutdown();
    connectionManager = null;
    sessionTimeout = -1L;
  }

  /** Test the connection.  Returns a string describing the connection integrity.
  *@return the connection's status as a displayable string.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    getSession();
    // MHL
    return super.check();
  }
  
  /**
   * Return a list of activities that this connector generates. The connector
   * does NOT need to be connected before this method is called.
   * 
   * @return the set of activities.
   */
  @Override
  public String[] getActivitiesList() {
    return activitiesList;
  }

  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add(Messages.getString(locale,"TikaExtractor.TikaServerTabName"));
    Messages.outputResourceWithVelocity(out,locale,EDIT_CONFIGURATION_JS,null);
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInServerTab(velocityContext,out,parameters);
    Messages.outputResourceWithVelocity(out,locale,EDIT_CONFIGURATION_SERVER_HTML,velocityContext);
  }
  
  
  /** Process a configuration post.
  * This method is called at the start of the connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException
  {
    String tikaHostname = variableContext.getParameter("tikaHostname");

    if (tikaHostname != null)
      parameters.setParameter(TikaConfig.PARAM_TIKAHOSTNAME,tikaHostname);

    String tikaPort = variableContext.getParameter("tikaPort");
    if (tikaPort != null)
      parameters.setParameter(TikaConfig.PARAM_TIKAPORT,tikaPort);

    return null;
  }
  
  /** View configuration.
  * This method is called in the body section of the connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    fillInServerTab(velocityContext,out,parameters);
    Messages.outputResourceWithVelocity(out,locale,VIEW_CONFIGURATION_HTML,velocityContext);
  }

  protected static void fillInServerTab(Map<String,Object> velocityContext, IHTTPOutput out, ConfigParams parameters)
    throws ManifoldCFException
  {
    String tikaHostname = parameters.getParameter(TikaConfig.PARAM_TIKAHOSTNAME);
    if (tikaHostname == null)
      tikaHostname = TikaConfig.TIKAHOSTNAME_DEFAULT;

    String tikaPort = parameters.getParameter(TikaConfig.PARAM_TIKAPORT);
    if (tikaPort == null)
      tikaPort = TikaConfig.TIKAPORT_DEFAULT;

    // Fill in context
    velocityContext.put("TIKAHOSTNAME", tikaHostname);
    velocityContext.put("TIKAPORT", tikaPort);
  }

  /**
   * Get an output version string, given an output specification. The output
   * version string is used to uniquely describe the pertinent details of the
   * output specification and the configuration, to allow the Connector
   * Framework to determine whether a document will need to be output again.
   * Note that the contents of the document cannot be considered by this method,
   * and that a different version string (defined in IRepositoryConnector) is
   * used to describe the version of the actual document.
   *
   * This method presumes that the connector object has been configured, and it
   * is thus able to communicate with the output data store should that be
   * necessary.
   * 
   * @param os
   *          is the current output specification for the job that is doing the
   *          crawling.
   * @return a string, of unlimited length, which uniquely describes output
   *         configuration and specification in such a way that if two such
   *         strings are equal, the document will not need to be sent again to
   *         the output data store.
   */
  @Override
  public VersionContext getPipelineDescription(Specification os) throws ManifoldCFException, ServiceInterruption {
    SpecPacker sp = new SpecPacker(os);
    return new VersionContext(sp.toPackedString(), params, os);
  }

  // We intercept checks pertaining to the document format and send modified
  // checks further down

  /**
   * Detect if a mime type is acceptable or not. This method is used to
   * determine whether it makes sense to fetch a document in the first place.
   * 
   * @param pipelineDescription
   *          is the document's pipeline version string, for this connection.
   * @param mimeType
   *          is the mime type of the document.
   * @param checkActivity
   *          is an object including the activities that can be performed by
   *          this method.
   * @return true if the mime type can be accepted by this connector.
   */
  @Override
  public boolean checkMimeTypeIndexable(VersionContext pipelineDescription, String mimeType,
      IOutputCheckActivity checkActivity) throws ManifoldCFException, ServiceInterruption {
    // We should see what Tika will transform
    // MHL
    // Do a downstream check
    return checkActivity.checkMimeTypeIndexable("text/plain;charset=utf-8");
  }

  /**
   * Pre-determine whether a document (passed here as a File object) is
   * acceptable or not. This method is used to determine whether a document
   * needs to be actually transferred. This hook is provided mainly to support
   * search engines that only handle a small set of accepted file types.
   * 
   * @param pipelineDescription
   *          is the document's pipeline version string, for this connection.
   * @param localFile
   *          is the local file to check.
   * @param checkActivity
   *          is an object including the activities that can be done by this
   *          method.
   * @return true if the file is acceptable, false if not.
   */
  @Override
  public boolean checkDocumentIndexable(VersionContext pipelineDescription, File localFile,
      IOutputCheckActivity checkActivity) throws ManifoldCFException, ServiceInterruption {
    // Document contents are not germane anymore, unless it looks like Tika
    // won't accept them.
    // Not sure how to check that...
    return true;
  }

  /**
   * Pre-determine whether a document's length is acceptable. This method is
   * used to determine whether to fetch a document in the first place.
   * 
   * @param pipelineDescription
   *          is the document's pipeline version string, for this connection.
   * @param length
   *          is the length of the document.
   * @param checkActivity
   *          is an object including the activities that can be done by this
   *          method.
   * @return true if the file is acceptable, false if not.
   */
  @Override
  public boolean checkLengthIndexable(VersionContext pipelineDescription, long length,
      IOutputCheckActivity checkActivity) throws ManifoldCFException, ServiceInterruption {
    // Always true
    return true;
  }

  /**
   * Add (or replace) a document in the output data store using the connector.
   * This method presumes that the connector object has been configured, and it
   * is thus able to communicate with the output data store should that be
   * necessary. The OutputSpecification is *not* provided to this method,
   * because the goal is consistency, and if output is done it must be
   * consistent with the output description, since that was what was partly used
   * to determine if output should be taking place. So it may be necessary for
   * this method to decode an output description string in order to determine
   * what should be done.
   * 
   * @param documentURI
   *          is the URI of the document. The URI is presumed to be the unique
   *          identifier which the output data store will use to process and
   *          serve the document. This URI is constructed by the repository
   *          connector which fetches the document, and is thus universal across
   *          all output connectors.
   * @param outputDescription
   *          is the description string that was constructed for this document
   *          by the getOutputDescription() method.
   * @param document
   *          is the document data to be processed (handed to the output data
   *          store).
   * @param authorityNameString
   *          is the name of the authority responsible for authorizing any
   *          access tokens passed in with the repository document. May be null.
   * @param activities
   *          is the handle to an object that the implementer of a pipeline
   *          connector may use to perform operations, such as logging
   *          processing activity, or sending a modified document to the next
   *          stage in the pipeline.
   * @return the document status (accepted or permanently rejected).
   * @throws IOException
   *           only if there's a stream error reading the document data.
   */
  @Override
  public int addOrReplaceDocumentWithException(String documentURI, VersionContext pipelineDescription,
      RepositoryDocument document, String authorityNameString, IOutputAddActivity activities)
      throws ManifoldCFException, ServiceInterruption, IOException {
    // First, make sure downstream pipeline will now accept
    // text/plain;charset=utf-8
    if (!activities.checkMimeTypeIndexable("text/plain;charset=utf-8")) {
      activities.noDocument();
      activities.recordActivity(null, ACTIVITY_EXTRACT, null, documentURI, activities.EXCLUDED_MIMETYPE,
          "Downstream pipeline rejected mime type 'text/plain;charset=utf-8'");
      return DOCUMENTSTATUS_REJECTED;
    }

    SpecPacker sp = new SpecPacker(pipelineDescription.getSpecification());

    getSession();
    
    // Tika server variables
    String mime = "";
    InputStream tikaServerIs = null;
    HttpResponse response = null;
    IOException tikaServerDownException = null;

    // Tika's API reads from an input stream and writes to an output Writer.
    // Since a RepositoryDocument includes readers and inputstreams exclusively,
    // AND all downstream
    // processing needs to occur in a ManifoldCF thread, we have some
    // constraints on the architecture we need to get this done:
    // (1) The principle worker thread must call the downstream pipeline send()
    // method.
    // (2) The callee of the send() method must call a reader in the Repository
    // Document.
    // (3) The Reader, if its databuffer is empty, must pull more data from the
    // original input stream and hand it to Tika, which populates the Reader's
    // databuffer.
    // So all this can be done in one thread, with some work, and the creation
    // of a special InputStream or Reader implementation. Where it fails,
    // though, is the
    // requirement that tika-extracted metadata be included in the
    // RepositoryDocument right from the beginning. Effectively this means that
    // the entire document
    // must be parsed before it is handed downstream -- so basically a temporary
    // file (or in-memory buffer if small enough) must be created.
    // Instead of the elegant flow above, we have the following:
    // (1) Create a temporary file (or in-memory buffer if file is small enough)
    // (2) Run Tika to completion, streaming content output to temporary file
    // (3) Modify RepositoryDocument to read from temporary file, and include
    // Tika-extracted metadata
    // (4) Call downstream document processing

    DestinationStorage ds;

    if (document.getBinaryLength() <= inMemoryMaximumFile) {
      ds = new MemoryDestinationStorage((int) document.getBinaryLength());
    } else {
      ds = new FileDestinationStorage();
    }
    try {
      Metadata metadata = new Metadata();
      if (document.getFileName() != null) {
        metadata.add(TikaMetadataKeys.RESOURCE_NAME_KEY, document.getFileName());
        metadata.add("stream_name", document.getFileName());
      }
      if (document.getMimeType() != null) {
        mime = document.getMimeType();
        metadata.add("Content-Type", mime);
      }
      metadata.add("stream_size", new Long(document.getBinaryLength()).toString());

      // We only log the extraction
      long startTime = System.currentTimeMillis();
      String resultCode = "OK";
      String description = null;
      Long length = null;

      try {
        try {

          // Make a copy of the original stream as it needs to be sent two
          // times to Tika
          // one for the metadata and one for the content
          IOUtils.copy(document.getBinaryStream(), ds.getOutputStream());

          // Metadata
          HttpPut httpPut = new HttpPut(metaURI);
          if (!mime.isEmpty()) {
            httpPut.addHeader("Content-Type", mime);
          }
          httpPut.addHeader("Accept", "application/json");
          HttpEntity entity = new InputStreamEntity(ds.getInputStream());
          httpPut.setEntity(entity);
          try {
            response = client.execute(tikaHost, httpPut);
          } catch (IOExceptione e) {
            // Retry 3 times, 10000 ms between retries, and abort if doesn't work
            final long currentTime = System.currentTimeMillis();
            throw new ServiceInterruption("Tika down, retrying: "+e.getMessage(),e,currentTime + 10000L,
              -1L,3,true);
          }
          int responseCode = response.getStatusLine().getStatusCode();
          if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 204) {
            tikaServerIs = response.getEntity().getContent();
            try {
              final BufferedReader br = new BufferedReader(new InputStreamReader(tikaServerIs));
              final JSONParser parser = new JSONParser();
              JSONObject metaJson;
              final StringBuilder sb = new StringBuilder();
              String output;
              while ((output = br.readLine()) != null) {
                sb.append(output);
              }
              metaJson = (JSONObject) parser.parse(sb.toString());
              for (Object key : metaJson.keySet()) {
                metadata.add(key.toString(), metaJson.get(key).toString());
              }
            } finally {
              tikaServerIs.close();
            }
          } else {
            activities.noDocument();
            if (responseCode == 422) {
              resultCode = "TIKASERVERREJECTS";
              description = "Tika Server rejected document with the following reason: "
                  + response.getStatusLine().getReasonPhrase();
              handleTikaServerRejects(description);
            } else {
              resultCode = "TIKASERVERERROR";
              description = "Tika Server failed to parse document with the following error: "
                  + response.getStatusLine().getReasonPhrase();
              handleTikaServerError(description);
            }
            return DOCUMENTSTATUS_REJECTED;
          }

          // Content
          httpPut = new HttpPut(sp.contentURI);
          if (!mime.isEmpty()) {
            httpPut.addHeader("Content-Type", mime);
          }
          httpPut.addHeader("Accept", "text/plain");
          entity = new InputStreamEntity(ds.getInputStream());
          httpPut.setEntity(entity);
          try {
            response = client.execute(tikaHost, httpPut);
          } catch (IOException e) {
            // Retry 3 times, 10000 ms between retries, and abort if doesn't work
            final long currentTime = System.currentTimeMillis();
            throw new ServiceInterruption("Tika down, retrying: "+e.getMessage(),e,currentTime + 10000L,
              -1L,3,true);
          }

          responseCode = response.getStatusLine().getStatusCode();
          if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 204) {
            tikaServerIs = response.getEntity().getContent();
            try {
              ds.close();
              ds = new FileDestinationStorage();
              IOUtils.copyLarge(tikaServerIs, ds.getOutputStream(), 0L, sp.writeLimit);
              length = new Long(ds.getBinaryLength());
            } finally {
              tikaServerIs.close();
            }
          } else {
            activities.noDocument();
            if (responseCode == 422) {
              resultCode = "TIKASERVERREJECTS";
              description = "Tika Server rejected document with the following reason: "
                  + response.getStatusLine().getReasonPhrase();
              handleTikaServerRejects(description);
            } else {
              resultCode = "TIKASERVERERROR";
              description = "Tika Server failed to parse document with the following error: "
                  + response.getStatusLine().getReasonPhrase();
              handleTikaServerError(description);
            }
            return DOCUMENTSTATUS_REJECTED;
          }

        } catch (IOException | ParseException e) {
          resultCode = "TIKASERVERRESPONSEISSUE";
          description = e.getMessage();
          int rval;
          if (e instanceof IOException) {
            rval = handleTikaServerException((IOException) e);
          } else {
            rval = handleTikaServerException((ParseException) e);
          }
          if (rval == DOCUMENTSTATUS_REJECTED) {
            activities.noDocument();
          }
          return rval;
        }

        if (!activities.checkLengthIndexable(ds.getBinaryLength())) {
          activities.noDocument();
          resultCode = activities.EXCLUDED_LENGTH;
          description = "Downstream pipeline rejected document with length " + ds.getBinaryLength();
          return DOCUMENTSTATUS_REJECTED;
        }

      } finally {
        // Log the extraction processing
        activities.recordActivity(new Long(startTime), ACTIVITY_EXTRACT, length, documentURI, resultCode, description);
      }

      // Parsing complete!
      // Create a copy of Repository Document
      RepositoryDocument docCopy = document.duplicate();

      // Get new stream length
      long newBinaryLength = ds.getBinaryLength();
      // Open new input stream
      InputStream is = ds.getInputStream();

      try {
        docCopy.setBinary(is, newBinaryLength);

        // Set up all metadata from Tika. We may want to run this through a
        // mapper eventually...
        String[] metaNames = metadata.names();
        for (String mName : metaNames) {
          String value = metadata.get(mName);
          if (sp.lowerNames()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mName.length(); i++) {
              char ch = mName.charAt(i);
              if (!Character.isLetterOrDigit(ch))
                ch = '_';
              else
                ch = Character.toLowerCase(ch);
              sb.append(ch);
            }
            mName = sb.toString();
          }
          String target = sp.getMapping(mName);
          if (target != null) {
            docCopy.addField(target, value);
          } else {
            if (sp.keepAllMetadata()) {
              docCopy.addField(mName, value);
            }
          }
        }

        // Send new document downstream
        return activities.sendDocument(documentURI, docCopy);
      } finally {
        is.close();
      }
    } finally {
      ds.close();
    }

  }

  /**
   * Obtain the name of the form check javascript method to call.
   * 
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @return the name of the form check javascript method.
   */
  @Override
  public String getFormCheckJavascriptMethodName(int connectionSequenceNumber) {
    return "s" + connectionSequenceNumber + "_checkSpecification";
  }

  /**
   * Obtain the name of the form presave check javascript method to call.
   * 
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @return the name of the form presave check javascript method.
   */
  @Override
  public String getFormPresaveCheckJavascriptMethodName(int connectionSequenceNumber) {
    return "s" + connectionSequenceNumber + "_checkSpecificationForSave";
  }

  /**
   * Output the specification header section. This method is called in the head
   * section of a job page which has selected a pipeline connection of the
   * current type. Its purpose is to add the required tabs to the list, and to
   * output any javascript methods that might be needed by the job editing HTML.
   * 
   * @param out
   *          is the output to which any HTML should be sent.
   * @param locale
   *          is the preferred local of the output.
   * @param os
   *          is the current pipeline specification for this connection.
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @param tabsArray
   *          is an array of tab names. Add to this array any tab names that are
   *          specific to the connector.
   */
  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale, Specification os, int connectionSequenceNumber,
      List<String> tabsArray) throws ManifoldCFException, IOException {
    Map<String, Object> paramMap = new HashMap<String, Object>();
    paramMap.put("SEQNUM", Integer.toString(connectionSequenceNumber));

    tabsArray.add(Messages.getString(locale, "TikaExtractor.FieldMappingTabName"));
    tabsArray.add(Messages.getString(locale, "TikaExtractor.ExceptionsTabName"));

    // Fill in the specification header map, using data from all tabs.
    fillInFieldMappingSpecificationMap(paramMap, os);
    fillInExceptionsSpecificationMap(paramMap, os);

    Messages.outputResourceWithVelocity(out, locale, EDIT_SPECIFICATION_JS, paramMap);
  }

  /**
   * Output the specification body section. This method is called in the body
   * section of a job page which has selected a pipeline connection of the
   * current type. Its purpose is to present the required form elements for
   * editing. The coder can presume that the HTML that is output from this
   * configuration will be within appropriate <html>, <body>, and <form> tags.
   * The name of the form is "editjob".
   * 
   * @param out
   *          is the output to which any HTML should be sent.
   * @param locale
   *          is the preferred local of the output.
   * @param os
   *          is the current pipeline specification for this job.
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @param actualSequenceNumber
   *          is the connection within the job that has currently been selected.
   * @param tabName
   *          is the current tab name.
   */
  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale, Specification os, int connectionSequenceNumber,
      int actualSequenceNumber, String tabName) throws ManifoldCFException, IOException {
    Map<String, Object> paramMap = new HashMap<String, Object>();

    // Set the tab name
    paramMap.put("TABNAME", tabName);
    paramMap.put("SEQNUM", Integer.toString(connectionSequenceNumber));
    paramMap.put("SELECTEDNUM", Integer.toString(actualSequenceNumber));

    // Fill in the field mapping tab data
    fillInFieldMappingSpecificationMap(paramMap, os);
    fillInExceptionsSpecificationMap(paramMap, os);

    Messages.outputResourceWithVelocity(out, locale, EDIT_SPECIFICATION_FIELDMAPPING_HTML, paramMap);
    Messages.outputResourceWithVelocity(out, locale, EDIT_SPECIFICATION_EXCEPTIONS_HTML, paramMap);
  }

  /**
   * Process a specification post. This method is called at the start of job's
   * edit or view page, whenever there is a possibility that form data for a
   * connection has been posted. Its purpose is to gather form information and
   * modify the transformation specification accordingly. The name of the posted
   * form is "editjob".
   * 
   * @param variableContext
   *          contains the post data, including binary file-upload information.
   * @param locale
   *          is the preferred local of the output.
   * @param os
   *          is the current pipeline specification for this job.
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @return null if all is well, or a string error message if there is an error
   *         that should prevent saving of the job (and cause a redirection to
   *         an error page).
   */
  @Override
  public String processSpecificationPost(IPostParameters variableContext, Locale locale, Specification os,
      int connectionSequenceNumber) throws ManifoldCFException {
    String seqPrefix = "s" + connectionSequenceNumber + "_";

    String x;

    x = variableContext.getParameter(seqPrefix + "fieldmapping_count");
    if (x != null && x.length() > 0) {
      // About to gather the fieldmapping nodes, so get rid of the old ones.
      int i = 0;
      while (i < os.getChildCount()) {
        SpecificationNode node = os.getChild(i);
        if (node.getType().equals(TikaConfig.NODE_FIELDMAP) || node.getType().equals(TikaConfig.NODE_KEEPMETADATA)
            || node.getType().equals(TikaConfig.NODE_LOWERNAMES) || node.getType().equals(TikaConfig.NODE_WRITELIMIT))
          os.removeChild(i);
        else
          i++;
      }
      int count = Integer.parseInt(x);
      i = 0;
      while (i < count) {
        String prefix = seqPrefix + "fieldmapping_";
        String suffix = "_" + Integer.toString(i);
        String op = variableContext.getParameter(prefix + "op" + suffix);
        if (op == null || !op.equals("Delete")) {
          // Gather the fieldmap etc.
          String source = variableContext.getParameter(prefix + "source" + suffix);
          String target = variableContext.getParameter(prefix + "target" + suffix);
          if (target == null)
            target = "";
          SpecificationNode node = new SpecificationNode(TikaConfig.NODE_FIELDMAP);
          node.setAttribute(TikaConfig.ATTRIBUTE_SOURCE, source);
          node.setAttribute(TikaConfig.ATTRIBUTE_TARGET, target);
          os.addChild(os.getChildCount(), node);
        }
        i++;
      }

      String addop = variableContext.getParameter(seqPrefix + "fieldmapping_op");
      if (addop != null && addop.equals("Add")) {
        String source = variableContext.getParameter(seqPrefix + "fieldmapping_source");
        String target = variableContext.getParameter(seqPrefix + "fieldmapping_target");
        if (target == null)
          target = "";
        SpecificationNode node = new SpecificationNode(TikaConfig.NODE_FIELDMAP);
        node.setAttribute(TikaConfig.ATTRIBUTE_SOURCE, source);
        node.setAttribute(TikaConfig.ATTRIBUTE_TARGET, target);
        os.addChild(os.getChildCount(), node);
      }

      // Gather the keep all metadata parameter to be the last one
      SpecificationNode node = new SpecificationNode(TikaConfig.NODE_KEEPMETADATA);
      String keepAll = variableContext.getParameter(seqPrefix + "keepallmetadata");
      if (keepAll != null) {
        node.setAttribute(TikaConfig.ATTRIBUTE_VALUE, keepAll);
      } else {
        node.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "false");
      }
      // Add the new keepallmetadata config parameter
      os.addChild(os.getChildCount(), node);

      SpecificationNode node2 = new SpecificationNode(TikaConfig.NODE_LOWERNAMES);
      String lower = variableContext.getParameter(seqPrefix + "lowernames");
      if (lower != null) {
        node2.setAttribute(TikaConfig.ATTRIBUTE_VALUE, lower);
      } else {
        node2.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "false");
      }
      os.addChild(os.getChildCount(), node2);

      SpecificationNode node3 = new SpecificationNode(TikaConfig.NODE_WRITELIMIT);
      String writeLimit = variableContext.getParameter(seqPrefix + "writelimit");
      if (writeLimit != null) {
        node3.setAttribute(TikaConfig.ATTRIBUTE_VALUE, writeLimit);
      } else {
        node3.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "");
      }
      os.addChild(os.getChildCount(), node3);
    }

    if (variableContext.getParameter(seqPrefix + "ignoretikaexceptions_present") != null) {
      int i = 0;
      while (i < os.getChildCount()) {
        SpecificationNode node = os.getChild(i);
        if (node.getType().equals(TikaConfig.NODE_IGNORETIKAEXCEPTION))
          os.removeChild(i);
        else
          i++;
      }

      String value = variableContext.getParameter(seqPrefix + "ignoretikaexceptions");
      if (value == null)
        value = "false";

      SpecificationNode node = new SpecificationNode(TikaConfig.NODE_IGNORETIKAEXCEPTION);
      node.setAttribute(TikaConfig.ATTRIBUTE_VALUE, value);
      os.addChild(os.getChildCount(), node);
    }

    x = variableContext.getParameter(seqPrefix + "tikaserver");
    if (x != null) {
      int i = 0;
      while (i < os.getChildCount()) {
        SpecificationNode node = os.getChild(i);
        if (node.getType().equals(TikaConfig.NODE_TIKASERVER) || node.getType().equals(TikaConfig.NODE_TIKAHOSTNAME)
            || node.getType().equals(TikaConfig.NODE_TIKAPORT) || node.getType().equals(TikaConfig.NODE_TIKARETRY))
          os.removeChild(i);
        else
          i++;
      }

      SpecificationNode node = new SpecificationNode(TikaConfig.NODE_TIKASERVER);
      String tikaServer = variableContext.getParameter(seqPrefix + "tikaserver");
      if (tikaServer != null) {
        node.setAttribute(TikaConfig.ATTRIBUTE_VALUE, tikaServer);
      } else {
        node.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "false");
      }
      // Add the new tikaserver config parameter
      os.addChild(os.getChildCount(), node);

      SpecificationNode node2 = new SpecificationNode(TikaConfig.NODE_TIKAHOSTNAME);
      String tikaHostname = variableContext.getParameter(seqPrefix + "tikahostname");
      if (tikaHostname != null) {
        node2.setAttribute(TikaConfig.ATTRIBUTE_VALUE, tikaHostname);
      } else {
        node2.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "");
      }
      // Add the new tikahostname config parameter
      os.addChild(os.getChildCount(), node2);

      SpecificationNode node3 = new SpecificationNode(TikaConfig.NODE_TIKAPORT);
      String tikaPort = variableContext.getParameter(seqPrefix + "tikaport");
      if (tikaPort != null) {
        node3.setAttribute(TikaConfig.ATTRIBUTE_VALUE, tikaPort);
      } else {
        node3.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "");
      }
      // Add the new tikaport config parameter
      os.addChild(os.getChildCount(), node3);

      SpecificationNode node4 = new SpecificationNode(TikaConfig.NODE_TIKARETRY);
      String tikaRetry = variableContext.getParameter(seqPrefix + "tikaretry");
      if (tikaRetry != null) {
        node4.setAttribute(TikaConfig.ATTRIBUTE_VALUE, tikaRetry);
      } else {
        node4.setAttribute(TikaConfig.ATTRIBUTE_VALUE, "");
      }
      // Add the new tikaport config parameter
      os.addChild(os.getChildCount(), node4);
    }

    return null;
  }

  /**
   * View specification. This method is called in the body section of a job's
   * view page. Its purpose is to present the pipeline specification information
   * to the user. The coder can presume that the HTML that is output from this
   * configuration will be within appropriate <html> and <body> tags.
   * 
   * @param out
   *          is the output to which any HTML should be sent.
   * @param locale
   *          is the preferred local of the output.
   * @param connectionSequenceNumber
   *          is the unique number of this connection within the job.
   * @param os
   *          is the current pipeline specification for this job.
   */
  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, Specification os, int connectionSequenceNumber)
      throws ManifoldCFException, IOException {
    Map<String, Object> paramMap = new HashMap<String, Object>();
    paramMap.put("SEQNUM", Integer.toString(connectionSequenceNumber));

    // Fill in the map with data from all tabs
    fillInFieldMappingSpecificationMap(paramMap, os);
    fillInExceptionsSpecificationMap(paramMap, os);

    Messages.outputResourceWithVelocity(out, locale, VIEW_SPECIFICATION_HTML, paramMap);

  }

  protected static void fillInTikaTypeSpecificationMap(Map<String, Object> paramMap, Specification os) {
    String tikaServer = "false";
    String tikaHostname = TikaConfig.TIKAHOSTNAME_DEFAULT;
    String tikaPort = String.valueOf(TikaConfig.TIKAPORT_DEFAULT);
    String tikaRetry = String.valueOf(TikaConfig.TIKARETRY_DEFAULT);
    for (int i = 0; i < os.getChildCount(); i++) {
      SpecificationNode sn = os.getChild(i);
      if (sn.getType().equals(TikaConfig.NODE_TIKASERVER)) {
        tikaServer = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      } else if (sn.getType().equals(TikaConfig.NODE_TIKAHOSTNAME)) {
        tikaHostname = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      } else if (sn.getType().equals(TikaConfig.NODE_TIKAPORT)) {
        tikaPort = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      } else if (sn.getType().equals(TikaConfig.NODE_TIKARETRY)) {
        tikaRetry = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      }
    }
    paramMap.put("TIKASERVER", tikaServer);
    paramMap.put("TIKAHOSTNAME", tikaHostname);
    paramMap.put("TIKAPORT", tikaPort);
    paramMap.put("TIKARETRY", tikaRetry);
  }

  protected static void fillInFieldMappingSpecificationMap(Map<String, Object> paramMap, Specification os) {
    // Prep for field mappings
    List<Map<String, String>> fieldMappings = new ArrayList<Map<String, String>>();
    String keepAllMetadataValue = "true";
    String lowernamesValue = "false";
    String writeLimitValue = "";
    for (int i = 0; i < os.getChildCount(); i++) {
      SpecificationNode sn = os.getChild(i);
      if (sn.getType().equals(TikaConfig.NODE_FIELDMAP)) {
        String source = sn.getAttributeValue(TikaConfig.ATTRIBUTE_SOURCE);
        String target = sn.getAttributeValue(TikaConfig.ATTRIBUTE_TARGET);
        String targetDisplay;
        if (target == null) {
          target = "";
          targetDisplay = "(remove)";
        } else
          targetDisplay = target;
        Map<String, String> fieldMapping = new HashMap<String, String>();
        fieldMapping.put("SOURCE", source);
        fieldMapping.put("TARGET", target);
        fieldMapping.put("TARGETDISPLAY", targetDisplay);
        fieldMappings.add(fieldMapping);
      } else if (sn.getType().equals(TikaConfig.NODE_KEEPMETADATA)) {
        keepAllMetadataValue = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      } else if (sn.getType().equals(TikaConfig.NODE_LOWERNAMES)) {
        lowernamesValue = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      } else if (sn.getType().equals(TikaConfig.NODE_WRITELIMIT)) {
        writeLimitValue = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      }
    }
    paramMap.put("FIELDMAPPINGS", fieldMappings);
    paramMap.put("KEEPALLMETADATA", keepAllMetadataValue);
    paramMap.put("LOWERNAMES", lowernamesValue);
    paramMap.put("WRITELIMIT", writeLimitValue);
  }

  protected static void fillInExceptionsSpecificationMap(Map<String, Object> paramMap, Specification os) {
    String ignoreTikaExceptions = "true";
    for (int i = 0; i < os.getChildCount(); i++) {
      SpecificationNode sn = os.getChild(i);
      if (sn.getType().equals(TikaConfig.NODE_IGNORETIKAEXCEPTION)) {
        ignoreTikaExceptions = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
      }
    }
    paramMap.put("IGNORETIKAEXCEPTIONS", ignoreTikaExceptions);
  }

  protected static int handleTikaException(TikaException e)
      throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does Tika throw if it gets an IOException reading the stream??
    Logging.ingest.warn("Tika: Tika exception extracting: " + e.getMessage(), e);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleTikaServerRejects(String reason)
      throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does Tika throw if it gets an IOException reading the stream??
    Logging.ingest.warn("Tika Server: Tika Server rejects: " + reason);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleTikaServerError(String description)
      throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does Tika throw if it gets an IOException reading the stream??
    Logging.ingest.warn("Tika Server: Tika Server error: " + description);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleTikaServerException(IOException e)
      throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does Tika throw if it gets an IOException reading the stream??
    Logging.ingest.warn("Tika: Tika exception extracting: " + e.getMessage(), e);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleTikaServerException(ParseException e)
      throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does Tika throw if it gets an IOException reading the stream??
    Logging.ingest.warn("Tika: Tika exception extracting: " + e.getMessage(), e);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleSaxException(SAXException e) throws IOException, ManifoldCFException, ServiceInterruption {
    // MHL - what does this mean?
    Logging.ingest.warn("Tika: SAX exception extracting: " + e.getMessage(), e);
    return DOCUMENTSTATUS_REJECTED;
  }

  protected static int handleIOException(IOException e) throws ManifoldCFException {
    // IOException reading from our local storage...
    if (e instanceof InterruptedIOException)
      throw new ManifoldCFException(e.getMessage(), e, ManifoldCFException.INTERRUPTED);
    throw new ManifoldCFException(e.getMessage(), e);
  }

  protected static interface DestinationStorage {
    /**
     * Get the output stream to write to. Caller should explicitly close this
     * stream when done writing.
     */
    public OutputStream getOutputStream() throws ManifoldCFException;

    /**
     * Get new binary length.
     */
    public long getBinaryLength() throws ManifoldCFException;

    /**
     * Get the input stream to read from. Caller should explicitly close this
     * stream when done reading.
     */
    public InputStream getInputStream() throws ManifoldCFException;

    /**
     * Close the object and clean up everything. This should be called when the
     * data is no longer needed.
     */
    public void close() throws ManifoldCFException;
  }

  protected static class FileDestinationStorage implements DestinationStorage {
    protected final File outputFile;
    protected final OutputStream outputStream;

    public FileDestinationStorage() throws ManifoldCFException {
      File outputFile;
      OutputStream outputStream;
      try {
        outputFile = File.createTempFile("mcftika", "tmp");
        outputStream = new FileOutputStream(outputFile);
      } catch (IOException e) {
        handleIOException(e);
        outputFile = null;
        outputStream = null;
      }
      this.outputFile = outputFile;
      this.outputStream = outputStream;
    }

    @Override
    public OutputStream getOutputStream() throws ManifoldCFException {
      return outputStream;
    }

    /**
     * Get new binary length.
     */
    @Override
    public long getBinaryLength() throws ManifoldCFException {
      return outputFile.length();
    }

    /**
     * Get the input stream to read from. Caller should explicitly close this
     * stream when done reading.
     */
    @Override
    public InputStream getInputStream() throws ManifoldCFException {
      try {
        return new FileInputStream(outputFile);
      } catch (IOException e) {
        handleIOException(e);
        return null;
      }
    }

    /**
     * Close the object and clean up everything. This should be called when the
     * data is no longer needed.
     */
    @Override
    public void close() throws ManifoldCFException {
      outputFile.delete();
    }

  }

  protected static class MemoryDestinationStorage implements DestinationStorage {
    protected final ByteArrayOutputStream outputStream;

    public MemoryDestinationStorage(int sizeHint) {
      outputStream = new ByteArrayOutputStream(sizeHint);
    }

    @Override
    public OutputStream getOutputStream() throws ManifoldCFException {
      return outputStream;
    }

    /**
     * Get new binary length.
     */
    @Override
    public long getBinaryLength() throws ManifoldCFException {
      return outputStream.size();
    }

    /**
     * Get the input stream to read from. Caller should explicitly close this
     * stream when done reading.
     */
    @Override
    public InputStream getInputStream() throws ManifoldCFException {
      return new ByteArrayInputStream(outputStream.toByteArray());
    }

    /**
     * Close the object and clean up everything. This should be called when the
     * data is no longer needed.
     */
    public void close() throws ManifoldCFException {
    }

  }

  protected static class SpecPacker {

    private final Map<String, String> sourceTargets = new HashMap<String, String>();
    private final boolean keepAllMetadata;
    private final boolean lowerNames;
    private final int writeLimit;
    private final boolean ignoreTikaException;

    public SpecPacker(Specification os) {
      boolean keepAllMetadata = true;
      boolean lowerNames = false;
      int writeLimit = TikaConfig.WRITELIMIT_DEFAULT;
      boolean ignoreTikaException = true;
      for (int i = 0; i < os.getChildCount(); i++) {
        SpecificationNode sn = os.getChild(i);

        if (sn.getType().equals(TikaConfig.NODE_KEEPMETADATA)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          keepAllMetadata = Boolean.parseBoolean(value);
        } else if (sn.getType().equals(TikaConfig.NODE_LOWERNAMES)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          lowerNames = Boolean.parseBoolean(value);
        } else if (sn.getType().equals(TikaConfig.NODE_WRITELIMIT)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          if (value.length() == 0) {
            writeLimit = TikaConfig.WRITELIMIT_DEFAULT;
          } else {
            writeLimit = Integer.parseInt(value);
          }
        } else if (sn.getType().equals(TikaConfig.NODE_FIELDMAP)) {
          String source = sn.getAttributeValue(TikaConfig.ATTRIBUTE_SOURCE);
          String target = sn.getAttributeValue(TikaConfig.ATTRIBUTE_TARGET);

          if (target == null) {
            target = "";
          }
          sourceTargets.put(source, target);
        } else if (sn.getType().equals(TikaConfig.NODE_IGNORETIKAEXCEPTION)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          ignoreTikaException = Boolean.parseBoolean(value);
        } else if (sn.getType().equals(TikaConfig.NODE_BOILERPLATEPROCESSOR)) {
          extractorClassName = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
        } else if (sn.getType().equals(TikaConfig.NODE_TIKAHOSTNAME)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          if (value.length() == 0) {
            tikaHostname = TikaConfig.TIKAHOSTNAME_DEFAULT;
          } else {
            tikaHostname = value;
          }
        } else if (sn.getType().equals(TikaConfig.NODE_TIKAPORT)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          if (value.length() == 0) {
            tikaPort = TikaConfig.TIKAPORT_DEFAULT;
          } else {
            tikaPort = Integer.parseInt(value);
          }
        } else if (sn.getType().equals(TikaConfig.NODE_TIKASERVER)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          if (value.length() == 0) {
            tikaServer = false;
          } else {
            tikaServer = Boolean.parseBoolean(value);
          }
        } else if (sn.getType().equals(TikaConfig.NODE_TIKARETRY)) {
          String value = sn.getAttributeValue(TikaConfig.ATTRIBUTE_VALUE);
          if (value.length() == 0) {
            tikaRetry = TikaConfig.TIKARETRY_DEFAULT;
          } else {
            tikaRetry = Long.parseLong(value);
          }
        }
      }
      this.keepAllMetadata = keepAllMetadata;
      this.lowerNames = lowerNames;
      this.writeLimit = writeLimit;
      this.ignoreTikaException = ignoreTikaException;
      this.extractorClassName = extractorClassName;
      this.tikaHostname = tikaHostname;
      this.tikaPort = tikaPort;
      this.tikaServer = tikaServer;
      this.tikaRetry = tikaRetry;
    }

    public String toPackedString() {
      StringBuilder sb = new StringBuilder();
      int i;

      // Mappings
      final String[] sortArray = new String[sourceTargets.size()];
      i = 0;
      for (String source : sourceTargets.keySet()) {
        sortArray[i++] = source;
      }
      java.util.Arrays.sort(sortArray);

      List<String> packedMappings = new ArrayList<String>();
      String[] fixedList = new String[2];
      for (String source : sortArray) {
        String target = sourceTargets.get(source);
        StringBuilder localBuffer = new StringBuilder();
        fixedList[0] = source;
        fixedList[1] = target;
        packFixedList(localBuffer, fixedList, ':');
        packedMappings.add(localBuffer.toString());
      }
      packList(sb, packedMappings, '+');

      // Keep all metadata
      if (keepAllMetadata)
        sb.append('+');
      else
        sb.append('-');
      if (lowerNames)
        sb.append('+');
      else
        sb.append('-');

      if (writeLimit != TikaConfig.WRITELIMIT_DEFAULT) {
        sb.append('+');
        sb.append(writeLimit);
      }

      if (ignoreTikaException)
        sb.append('+');
      else
        sb.append('-');

      if (extractorClassName != null) {
        sb.append('+');
        sb.append(extractorClassName);
      } else
        sb.append('-');

      return sb.toString();
    }

    public String getMapping(String source) {
      return sourceTargets.get(source);
    }

    public boolean keepAllMetadata() {
      return keepAllMetadata;
    }

    public boolean lowerNames() {
      return lowerNames;
    }

    public int writeLimit() {
      return writeLimit;
    }

    public boolean ignoreTikaException() {
      return ignoreTikaException;
    }

    public BoilerpipeExtractor getExtractorClassInstance() throws ManifoldCFException {
      if (extractorClassName == null)
        return null;
      try {
        ClassLoader loader = BoilerpipeExtractor.class.getClassLoader();
        Class extractorClass = loader.loadClass(extractorClassName);
        java.lang.reflect.Field f = extractorClass.getField("INSTANCE");
        return (BoilerpipeExtractor) f.get(null);
      } catch (ClassNotFoundException e) {
        throw new ManifoldCFException(
            "Boilerpipe extractor class '" + extractorClassName + "' not found: " + e.getMessage(), e);
      } catch (Exception e) {
        throw new ManifoldCFException(
            "Boilerpipe extractor class '" + extractorClassName + "' exception on instantiation: " + e.getMessage(), e);
      }
    }

  }

}