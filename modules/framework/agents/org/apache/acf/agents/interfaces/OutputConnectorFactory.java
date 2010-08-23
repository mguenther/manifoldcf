/* $Id: OutputConnectorFactory.java 959660 2010-07-01 13:46:10Z kwright $ */

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
import org.apache.acf.agents.system.ACF;

import java.util.*;
import java.io.*;
import java.lang.reflect.*;

/** This is the factory class for IOutputConnector objects.
*/
public class OutputConnectorFactory
{
  public static final String _rcsid = "@(#)$Id: OutputConnectorFactory.java 959660 2010-07-01 13:46:10Z kwright $";

  // Pool hash table.
  // Keyed by PoolKey; value is Pool
  protected static Map poolHash = new HashMap();

  // private static HashMap checkedOutConnectors = new HashMap();

  private OutputConnectorFactory()
  {
  }

  /** Install connector.
  *@param className is the class name.
  */
  public static void install(IThreadContext threadContext, String className)
    throws ACFException
  {
    IOutputConnector connector = getConnectorNoCheck(className);
    connector.install(threadContext);
  }

  /** Uninstall connector.
  *@param className is the class name.
  */
  public static void deinstall(IThreadContext threadContext, String className)
    throws ACFException
  {
    IOutputConnector connector = getConnectorNoCheck(className);
    connector.deinstall(threadContext);
  }

  /** Get the activities supported by this connector.
  *@param className is the class name.
  *@return the list of activities.
  */
  public static String[] getActivitiesList(IThreadContext threadContext, String className)
    throws ACFException
  {
    IOutputConnector connector = getConnector(threadContext, className);
    if (connector == null)
      return null;
    String[] values = connector.getActivitiesList();
    java.util.Arrays.sort(values);
    return values;
  }

  /** Output the configuration header section.
  */
  public static void outputConfigurationHeader(IThreadContext threadContext, String className, IHTTPOutput out, ConfigParams parameters, ArrayList tabsArray)
    throws ACFException, IOException
  {
    IOutputConnector connector = getConnector(threadContext, className);
    if (connector == null)
      return;
    connector.outputConfigurationHeader(threadContext,out,parameters,tabsArray);
  }

  /** Output the configuration body section.
  */
  public static void outputConfigurationBody(IThreadContext threadContext, String className, IHTTPOutput out, ConfigParams parameters, String tabName)
    throws ACFException, IOException
  {
    IOutputConnector connector = getConnector(threadContext, className);
    if (connector == null)
      return;
    connector.outputConfigurationBody(threadContext,out,parameters,tabName);
  }

  /** Process configuration post data for a connector.
  */
  public static String processConfigurationPost(IThreadContext threadContext, String className, IPostParameters variableContext, ConfigParams configParams)
    throws ACFException
  {
    IOutputConnector connector = getConnector(threadContext, className);
    if (connector == null)
      return null;
    return connector.processConfigurationPost(threadContext,variableContext,configParams);
  }
  
  /** View connector configuration.
  */
  public static void viewConfiguration(IThreadContext threadContext, String className, IHTTPOutput out, ConfigParams configParams)
    throws ACFException, IOException
  {
    IOutputConnector connector = getConnector(threadContext, className);
    // We want to be able to view connections even if they have unregistered connectors.
    if (connector == null)
      return;
    connector.viewConfiguration(threadContext,out,configParams);
  }

  /** Get an output connector instance, without checking for installed connector.
  *@param className is the class name.
  *@return the instance.
  */
  public static IOutputConnector getConnectorNoCheck(String className)
    throws ACFException
  {
    try
    {
      Class theClass = ACF.findClass(className);
      Class[] argumentClasses = new Class[0];
      // Look for a constructor
      Constructor c = theClass.getConstructor(argumentClasses);
      Object[] arguments = new Object[0];
      Object o = c.newInstance(arguments);
      if (!(o instanceof IOutputConnector))
        throw new ACFException("Class '"+className+"' does not implement IOutputConnector.");
      return (IOutputConnector)o;
    }
    catch (InvocationTargetException e)
    {
      Throwable z = e.getTargetException();
      if (z instanceof Error)
        throw (Error)z;
      else
        throw (ACFException)z;
    }
    catch (ClassNotFoundException e)
    {
      throw new ACFException("No output connector class '"+className+"' was found.",
        e);
    }
    catch (NoSuchMethodException e)
    {
      throw new ACFException("No appropriate constructor for IOutputConnector implementation '"+
        className+"'.  Need xxx(ConfigParams).",
        e);
    }
    catch (SecurityException e)
    {
      throw new ACFException("Protected constructor for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalAccessException e)
    {
      throw new ACFException("Unavailable constructor for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalArgumentException e)
    {
      throw new ACFException("Shouldn't happen!!!",e);
    }
    catch (InstantiationException e)
    {
      throw new ACFException("InstantiationException for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (ExceptionInInitializerError e)
    {
      throw new ACFException("ExceptionInInitializerError for IOutputConnector implementation '"+className+"'",
        e);
    }

  }

  /** Get an output connector instance.
  *@param className is the class name.
  *@return the instance.
  */
  protected static IOutputConnector getConnector(IThreadContext threadContext, String className)
    throws ACFException
  {
    IOutputConnectorManager connMgr = OutputConnectorManagerFactory.make(threadContext);
    if (connMgr.isInstalled(className) == false)
      return null;

    try
    {
      Class theClass = ACF.findClass(className);
      Class[] argumentClasses = new Class[0];
      // Look for a constructor
      Constructor c = theClass.getConstructor(argumentClasses);
      Object[] arguments = new Object[0];
      Object o = c.newInstance(arguments);
      if (!(o instanceof IOutputConnector))
        throw new ACFException("Class '"+className+"' does not implement IOutputConnector.");
      return (IOutputConnector)o;
    }
    catch (InvocationTargetException e)
    {
      Throwable z = e.getTargetException();
      if (z instanceof Error)
        throw (Error)z;
      else
        throw (ACFException)z;
    }
    catch (ClassNotFoundException e)
    {
      // This MAY mean that an existing connector has been uninstalled; check out this possibility!
      // We return null because that is the signal that we cannot get a connector instance for that reason.
      if (connMgr.isInstalled(className) == false)
        return null;

      throw new ACFException("No output connector class '"+className+"' was found.",
        e);
    }
    catch (NoSuchMethodException e)
    {
      throw new ACFException("No appropriate constructor for IOutputConnector implementation '"+
        className+"'.  Need xxx(ConfigParams).",
        e);
    }
    catch (SecurityException e)
    {
      throw new ACFException("Protected constructor for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalAccessException e)
    {
      throw new ACFException("Unavailable constructor for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalArgumentException e)
    {
      throw new ACFException("Shouldn't happen!!!",e);
    }
    catch (InstantiationException e)
    {
      throw new ACFException("InstantiationException for IOutputConnector implementation '"+className+"'",
        e);
    }
    catch (ExceptionInInitializerError e)
    {
      throw new ACFException("ExceptionInInitializerError for IOutputConnector implementation '"+className+"'",
        e);
    }

  }

  /** Get multiple output connectors, all at once.  Do this in a particular order
  * so that any connector exhaustion will not cause a deadlock.
  */
  public static IOutputConnector[] grabMultiple(IThreadContext threadContext,
    String[] orderingKeys, String[] classNames, ConfigParams[] configInfos, int[] maxPoolSizes)
    throws ACFException
  {
    IOutputConnector[] rval = new IOutputConnector[classNames.length];
    HashMap orderMap = new HashMap();
    int i = 0;
    while (i < orderingKeys.length)
    {
      if (orderMap.get(orderingKeys[i]) != null)
        throw new ACFException("Found duplicate order key");
      orderMap.put(orderingKeys[i],new Integer(i));
      i++;
    }
    java.util.Arrays.sort(orderingKeys);
    i = 0;
    while (i < orderingKeys.length)
    {
      String orderingKey = orderingKeys[i];
      int index = ((Integer)orderMap.get(orderingKey)).intValue();
      String className = classNames[index];
      ConfigParams cp = configInfos[index];
      int maxPoolSize = maxPoolSizes[index];
      try
      {
        IOutputConnector connector = grab(threadContext,className,cp,maxPoolSize);
        rval[index] = connector;
      }
      catch (Throwable e)
      {
        while (i > 0)
        {
          i--;
          orderingKey = orderingKeys[i];
          index = ((Integer)orderMap.get(orderingKey)).intValue();
          try
          {
            release(rval[index]);
          }
          catch (ACFException e2)
          {
          }
        }
        if (e instanceof ACFException)
        {
          throw (ACFException)e;
        }
        throw (Error)e;
      }
      i++;
    }
    return rval;
  }

  /** Get an output connector.
  * The connector is specified by its class and its parameters.
  *@param threadContext is the current thread context.
  *@param className is the name of the class to get a connector for.
  *@param configInfo are the name/value pairs constituting configuration info
  * for this class.
  */
  public static IOutputConnector grab(IThreadContext threadContext,
    String className, ConfigParams configInfo, int maxPoolSize)
    throws ACFException
  {
    // We want to get handles off the pool and use them.  But the
    // handles we fetch have to have the right config information.

    // Use the classname and config info to build a pool key.  This
    // key will be discarded if we actually have to save a key persistently,
    // since we avoid copying the configInfo unnecessarily.
    PoolKey pk = new PoolKey(className,configInfo);
    Pool p;
    synchronized (poolHash)
    {
      p = (Pool)poolHash.get(pk);
      if (p == null)
      {
        pk = new PoolKey(className,configInfo.duplicate());
        p = new Pool(pk,maxPoolSize);
        poolHash.put(pk,p);
      }
    }

    IOutputConnector rval = p.getConnector(threadContext);

    return rval;

  }

  /** Release multiple output connectors.
  */
  public static void releaseMultiple(IOutputConnector[] connectors)
    throws ACFException
  {
    int i = 0;
    ACFException currentException = null;
    while (i < connectors.length)
    {
      IOutputConnector c = connectors[i++];
      try
      {
        release(c);
      }
      catch (ACFException e)
      {
        if (currentException == null)
          currentException = e;
      }
    }
    if (currentException != null)
      throw currentException;
  }

  /** Release an output connector.
  *@param connector is the connector to release.
  */
  public static void release(IOutputConnector connector)
    throws ACFException
  {
    // If the connector is null, skip the release, because we never really got the connector in the first place.
    if (connector == null)
      return;

    // Figure out which pool this goes on, and put it there
    PoolKey pk = new PoolKey(connector.getClass().getName(),connector.getConfiguration());
    Pool p;
    synchronized (poolHash)
    {
      p = (Pool)poolHash.get(pk);
    }

    p.releaseConnector(connector);

    // synchronized (checkedOutConnectors)
    // {
    //      checkedOutConnectors.remove(connector.toString());
    // }

  }

  /** Idle notification for inactive output connector handles.
  * This method polls all inactive handles.
  */
  public static void pollAllConnectors(IThreadContext threadContext)
    throws ACFException
  {
    // System.out.println("Pool stats:");

    // Go through the whole pool and notify everyone
    synchronized (poolHash)
    {
      Iterator iter = poolHash.values().iterator();
      while (iter.hasNext())
      {
        Pool p = (Pool)iter.next();
        p.pollAll(threadContext);
      }
    }

    // System.out.println("About to check if any output connector instances have been abandoned...");
    // checkConnectors(System.currentTimeMillis());
  }

  /** Clean up all open output connector handles.
  * This method is called when the connector pool needs to be flushed,
  * to free resources.
  *@param threadContext is the local thread context.
  */
  public static void closeAllConnectors(IThreadContext threadContext)
    throws ACFException
  {
    // Go through the whole pool and clean it out
    synchronized (poolHash)
    {
      Iterator iter = poolHash.values().iterator();
      while (iter.hasNext())
      {
        Pool p = (Pool)iter.next();
        p.releaseAll(threadContext);
      }
    }
  }

  /** This is an immutable pool key class, which describes a pool in terms of two independent keys.
  */
  public static class PoolKey
  {
    protected String className;
    protected ConfigParams configInfo;

    /** Constructor.
    */
    public PoolKey(String className, Map configInfo)
    {
      this.className = className;
      this.configInfo = new ConfigParams(configInfo);
    }

    public PoolKey(String className, ConfigParams configInfo)
    {
      this.className = className;
      this.configInfo = configInfo;
    }

    /** Get the class name.
    *@return the class name.
    */
    public String getClassName()
    {
      return className;
    }

    /** Get the config info.
    *@return the params
    */
    public ConfigParams getParams()
    {
      return configInfo;
    }

    /** Hash code.
    */
    public int hashCode()
    {
      return className.hashCode() + configInfo.hashCode();
    }

    /** Equals operator.
    */
    public boolean equals(Object o)
    {
      if (!(o instanceof PoolKey))
        return false;

      PoolKey pk = (PoolKey)o;
      return pk.className.equals(className) && pk.configInfo.equals(configInfo);
    }

  }

  /** This class represents a value in the pool hash, which corresponds to a given key.
  */
  public static class Pool
  {
    protected ArrayList stack = new ArrayList();
    protected PoolKey key;
    protected int numFree;

    /** Constructor
    */
    public Pool(PoolKey pk, int maxCount)
    {
      key = pk;
      numFree = maxCount;
    }

    /** Grab an output connector.
    * If none exists, construct it using the information in the pool key.
    *@return the connector, or null if no connector could be connected.
    */
    public synchronized IOutputConnector getConnector(IThreadContext threadContext)
      throws ACFException
    {
      while (numFree == 0)
      {
        try
        {
          wait();
        }
        catch (InterruptedException e)
        {
          throw new ACFException("Interrupted: "+e.getMessage(),e,ACFException.INTERRUPTED);
        }
      }

      IOutputConnector rc;
      if (stack.size() == 0)
      {
        String className = key.getClassName();
        ConfigParams configParams = key.getParams();

        IOutputConnectorManager connMgr = OutputConnectorManagerFactory.make(threadContext);
        if (connMgr.isInstalled(className) == false)
          return null;

        try
        {
          Class theClass = ACF.findClass(className);
          Class[] argumentClasses = new Class[0];
          // Look for a constructor
          Constructor c = theClass.getConstructor(argumentClasses);
          Object[] arguments = new Object[0];
          Object o = c.newInstance(arguments);
          if (!(o instanceof IOutputConnector))
            throw new ACFException("Class '"+className+"' does not implement IOutputConnector.");
          rc = (IOutputConnector)o;
          rc.connect(configParams);
        }
        catch (InvocationTargetException e)
        {
          Throwable z = e.getTargetException();
          if (z instanceof Error)
            throw (Error)z;
          else
            throw (ACFException)z;
        }
        catch (ClassNotFoundException e)
        {
          // If we see this exception, it COULD mean that the connector was uninstalled, and we happened to get here
          // after that occurred.
          // We return null because that is the signal that we cannot get a connector instance for that reason.
          if (connMgr.isInstalled(className) == false)
            return null;

          throw new ACFException("No output connector class '"+className+"' was found.",
            e);
        }
        catch (NoSuchMethodException e)
        {
          throw new ACFException("No appropriate constructor for IOutputConnector implementation '"+
            className+"'.  Need xxx(ConfigParams).",
            e);
        }
        catch (SecurityException e)
        {
          throw new ACFException("Protected constructor for IOutputConnector implementation '"+className+"'",
            e);
        }
        catch (IllegalAccessException e)
        {
          throw new ACFException("Unavailable constructor for IOutputConnector implementation '"+className+"'",
            e);
        }
        catch (IllegalArgumentException e)
        {
          throw new ACFException("Shouldn't happen!!!",e);
        }
        catch (InstantiationException e)
        {
          throw new ACFException("InstantiationException for IOutputConnector implementation '"+className+"'",
            e);
        }
        catch (ExceptionInInitializerError e)
        {
          throw new ACFException("ExceptionInInitializerError for IOutputConnector implementation '"+className+"'",
            e);
        }
      }
      else
        rc = (IOutputConnector)stack.remove(stack.size()-1);

      numFree--;

      rc.setThreadContext(threadContext);
      return rc;
    }

    /** Release an output connector to the pool.
    *@param connector is the connector.
    */
    public synchronized void releaseConnector(IOutputConnector connector)
      throws ACFException
    {
      if (connector == null)
        return;

      // Make sure connector knows it's released
      connector.clearThreadContext();
      // Append
      stack.add(connector);
      numFree++;
      notifyAll();
    }

    /** Notify all free connectors.
    */
    public synchronized void pollAll(IThreadContext threadContext)
      throws ACFException
    {
      int i = 0;
      while (i < stack.size())
      {
        IOutputConnector rc = (IOutputConnector)stack.get(i++);
        // Notify
        rc.setThreadContext(threadContext);
        rc.poll();
        rc.clearThreadContext();
      }
    }

    /** Release all free connectors.
    */
    public synchronized void releaseAll(IThreadContext threadContext)
      throws ACFException
    {
      while (stack.size() > 0)
      {
        IOutputConnector rc = (IOutputConnector)stack.remove(stack.size()-1);
        // Disconnect
        rc.setThreadContext(threadContext);
        rc.disconnect();
        rc.clearThreadContext();
      }
    }

  }

}
