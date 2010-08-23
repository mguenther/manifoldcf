/* $Id: IncrementalIngesterFactory.java 921329 2010-03-10 12:44:20Z kwright $ */

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
package org.apache.acf.agents.interfaces;

import org.apache.acf.core.interfaces.*;
import org.apache.acf.agents.system.*;

/** Factory for getting incremental ingest manager handles.
*/
public class IncrementalIngesterFactory
{
  public static final String _rcsid = "@(#)$Id: IncrementalIngesterFactory.java 921329 2010-03-10 12:44:20Z kwright $";

  protected final static String ingestManager = "_IncrIngester_";

  private IncrementalIngesterFactory()
  {
  }


  /** Get an appropriate incremental ingest manager handle.
  */
  public static IIncrementalIngester make(IThreadContext threadContext)
    throws ACFException
  {
    Object o = threadContext.get(ingestManager);
    if (o == null || !(o instanceof IIncrementalIngester))
    {
      IDBInterface database = DBInterfaceFactory.make(threadContext,
        ACF.getMasterDatabaseName(),
        ACF.getMasterDatabaseUsername(),
        ACF.getMasterDatabasePassword());

      o = new org.apache.acf.agents.incrementalingest.IncrementalIngester(threadContext,database);
      threadContext.save(ingestManager,o);
    }
    return (IIncrementalIngester)o;
  }

}
