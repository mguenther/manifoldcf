/* $Id: AuthCheckThread.java 921329 2010-03-10 12:44:20Z kwright $ */

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
package org.apache.acf.authorities.system;

import org.apache.acf.core.interfaces.*;
import org.apache.acf.agents.interfaces.*;
import org.apache.acf.authorities.interfaces.*;
import org.apache.acf.authorities.system.Logging;
import java.util.*;
import java.lang.reflect.*;

/** This thread periodically calls the cleanup method in all connected repository connectors.  The ostensible purpose
* is to allow the connectors to shutdown idle connections etc.
*/
public class AuthCheckThread extends Thread
{
  public static final String _rcsid = "@(#)$Id: AuthCheckThread.java 921329 2010-03-10 12:44:20Z kwright $";

  // Local data
  protected RequestQueue requestQueue;

  /** Constructor.
  */
  public AuthCheckThread(String id, RequestQueue requestQueue)
    throws ACFException
  {
    super();
    this.requestQueue = requestQueue;
    setName("Auth check thread "+id);
    setDaemon(true);
  }

  public void run()
  {
    // Create a thread context object.
    IThreadContext threadContext = ThreadContextFactory.make();

    // Loop
    while (true)
    {
      // Do another try/catch around everything in the loop
      try
      {
        if (Thread.currentThread().isInterrupted())
          throw new ACFException("Interrupted",ACFException.INTERRUPTED);

        // Wait for a request.
        AuthRequest theRequest = requestQueue.getRequest();

        // Try to fill the request before going back to sleep.
        if (Logging.authorityService.isDebugEnabled())
        {
          Logging.authorityService.debug(" Calling connector class '"+theRequest.getClassName()+"'");
        }

        AuthorizationResponse response = null;
        Throwable exception = null;

        try
        {
          IAuthorityConnector connector = AuthorityConnectorFactory.grab(threadContext,
            theRequest.getClassName(),
            theRequest.getConfigurationParams(),
            theRequest.getMaxConnections());
          // If this is null, we MUST treat this as an "unauthorized" condition!!
          // We signal that by setting the exception value.
          try
          {
            if (connector == null)
              exception = new ACFException("Authority connector "+theRequest.getClassName()+" is not registered.");
            else
            {
              // Get the acl for the user
              try
              {
                response = connector.getAuthorizationResponse(theRequest.getUserID());
              }
              catch (ACFException e)
              {
                if (e.getErrorCode() == ACFException.INTERRUPTED)
                  throw e;
                Logging.authorityService.warn("Authority error: "+e.getMessage(),e);
                response = AuthorityConnectorFactory.getDefaultAuthorizationResponse(threadContext,theRequest.getClassName(),theRequest.getUserID());
              }

            }
          }
          finally
          {
            AuthorityConnectorFactory.release(connector);
          }
        }
        catch (ACFException e)
        {
          if (e.getErrorCode() == ACFException.INTERRUPTED)
            throw e;
          Logging.authorityService.warn("Authority connection exception: "+e.getMessage(),e);
          response = AuthorityConnectorFactory.getDefaultAuthorizationResponse(threadContext,theRequest.getClassName(),theRequest.getUserID());
          if (response == null)
            exception = e;
        }
        catch (Throwable e)
        {
          Logging.authorityService.warn("Authority connection error: "+e.getMessage(),e);
          response = AuthorityConnectorFactory.getDefaultAuthorizationResponse(threadContext,theRequest.getClassName(),theRequest.getUserID());
          if (response == null)
            exception = e;
        }

        // The request is complete
        theRequest.completeRequest(response,exception);

        // Repeat, and only go to sleep if there are no more requests.
      }
      catch (ACFException e)
      {
        if (e.getErrorCode() == ACFException.INTERRUPTED)
          break;

        // Log it, but keep the thread alive
        Logging.authorityService.error("Exception tossed: "+e.getMessage(),e);

        if (e.getErrorCode() == ACFException.SETUP_ERROR)
        {
          // Shut the whole system down!
          System.exit(1);
        }

      }
      catch (InterruptedException e)
      {
        // We're supposed to quit
        break;
      }
      catch (Throwable e)
      {
        // A more severe error - but stay alive
        Logging.authorityService.fatal("Error tossed: "+e.getMessage(),e);
      }
    }
  }

}
