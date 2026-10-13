/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller;

/**
 * Base Roller exception class.
 *
 * <p>The wrapped throwable goes to {@link Throwable}'s own cause chain. It used
 * to be held in a private field instead, with {@code printStackTrace} overridden
 * to print it -- which works only when something calls printStackTrace on this
 * exact object. Nested inside anything else, and everything is: a Spring
 * BeanCreationException, a surefire report, a logging framework, all of which
 * walk getCause(). The result was failures that printed "Caused by:
 * org.apache.roller.weblogger.WebloggerException" as the last line of the trace
 * and told you nothing at all. A malformed ORM mapping cost two debugging
 * sessions to that.
 */
public abstract class RollerException extends Exception {

    private static final long serialVersionUID = 1L;


    /**
     * Construct emtpy exception object.
     */
    protected RollerException() {
        super();
    }


    /**
     * Construct RollerException with message string.
     * @param s Error message string.
     */
    protected RollerException(String s) {
        super(s);
    }


    /**
     * Construct RollerException, wrapping existing throwable.
     * @param s Error message
     * @param t Existing connection to wrap.
     */
    protected RollerException(String s, Throwable t) {
        super(s, t);
    }


    /**
     * Construct RollerException, wrapping existing throwable.
     * @param t Existing exception to be wrapped.
     */
    protected RollerException(Throwable t) {
        super(t);
    }


    /**
     * Get root cause message.
     * @return Root cause message.
     */
    public String getRootCauseMessage() {
        String rcmessage = null;
        if (getCause()!=null) {
            if (getCause().getCause()!=null) {
                rcmessage = getCause().getCause().getMessage();
            }
            rcmessage = (rcmessage == null) ? getCause().getMessage() : rcmessage;
            rcmessage = (rcmessage == null) ? super.getMessage() : rcmessage;
            rcmessage = (rcmessage == null) ? "NONE" : rcmessage;
        }
        return rcmessage;
    }


}
