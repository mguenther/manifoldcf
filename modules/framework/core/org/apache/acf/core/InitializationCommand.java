package org.apache.acf.core;

import org.apache.acf.core.interfaces.ACFException;

/**
 * Interface for commands that initialize state of the connector framework. Among implementations available are:
 * - Database creation
 * - Registrations of agent
 * - Registrations of connectors
 *
 * @author Jettro Coenradie
 */
public interface InitializationCommand
{
  /**
   * Execute the command.
   *
   * @throws ACFException Thrown if the execution fails
   */
  void execute() throws ACFException;
}
