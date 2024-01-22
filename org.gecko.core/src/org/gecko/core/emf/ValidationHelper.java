/**
 * Copyright (c) 2012 - 2019 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.emf;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.Diagnostician;

/**
 * A helper class for {@link EObject} validation 
 * @author Mark Hoffmann
 * @since 26.03.2019
 */
public class ValidationHelper {
	
	public static final String VALIDATION_MESSAGE = "%sSource: [%s] Message [%s]";
	private String messageTemplate;
	
	/**
	 * Creates a new instance.
	 */
	ValidationHelper(String messageTemplate) {
		this.messageTemplate = messageTemplate == null ? VALIDATION_MESSAGE : messageTemplate;
	}
	
	/**
	 * Creates a new instance.
	 */
	ValidationHelper() {
		this(null);
	}
	
	/**
	 * Creates a new validation helper with a default message.
	 * The substitution uses {@link String#format(String, Object...)} and two {@link String} substitutions are expected.
	 * 1. source - where the error occurred
	 * 2. message - the diagnostic message
	 * @param messageTemplate the message text template.
	 * @return the {@link ValidationHelper} instance
	 */
	public static ValidationHelper create(String messageTemplate) {
		return new ValidationHelper(messageTemplate);
	}
	
	public static ValidationHelper create() {
		return create(null);
	}
	
	/**
	 * Validates an {@link EObject} and returns the validation text. A text is only returned, if the diagnostic
	 * of the validation is not of severity type OK. 
	 * @param object the object to be validated
	 * @return the error message text or <code>null</code>, if validation is OK
	 */
	public String validateNotOk(EObject object) {
		Diagnostic diagnostic = Diagnostician.INSTANCE.validate(object);
		if (diagnostic.getSeverity() != Diagnostic.OK) {
			return getDiagnosticMessage(diagnostic);
		}
		return null;
	}
	
    /**
     * Returns a message for a diagnostic
     * @param diagnostic the {@link Diagnostic}
     */
    public String getDiagnosticMessage(Diagnostic diagnostic) {
        StringBuilder message = new StringBuilder();
        createValidationMessage("", diagnostic, message);
        return message.toString();
    }
    
    protected void createValidationMessage(String indent, Diagnostic diagnostic, StringBuilder message) {
    	String separator = System.getProperty("line.separator");
        message.append(String.format(messageTemplate, indent, diagnostic.getSource(), diagnostic.getMessage()));
        message.append(separator);
        diagnostic.getChildren().forEach(d -> createValidationMessage("  " + indent , d, message));
    }

}
