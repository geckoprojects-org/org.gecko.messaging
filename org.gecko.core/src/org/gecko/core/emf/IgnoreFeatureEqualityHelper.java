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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil.EqualityHelper;

/**
 * Equality helper that can ignore certain features during equality check
 * @author Mark Hoffmann
 * @since 26.03.2019
 */
public class IgnoreFeatureEqualityHelper extends EqualityHelper {

	private static final long serialVersionUID = 1L;
	private final List<EStructuralFeature> ignoreFeatures = new LinkedList<EStructuralFeature>();

	public IgnoreFeatureEqualityHelper() {
	}

	/**
	 * Adds a new {@link EStructuralFeature} to be ignored while checking equality
	 * @param feature the {@link EStructuralFeature} to ignore
	 */
	public void addIgnoreFeature(EStructuralFeature feature) {
		if (!ignoreFeatures.contains(feature)) {
			ignoreFeatures.add(feature);
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.emf.ecore.util.EcoreUtil.EqualityHelper#haveEqualFeature(org.eclipse.emf.ecore.EObject, org.eclipse.emf.ecore.EObject, org.eclipse.emf.ecore.EStructuralFeature)
	 */
	@Override
	protected boolean haveEqualFeature(EObject eObject1, EObject eObject2, EStructuralFeature feature) {
		if (ignoreFeatures.contains(feature)) {
			return true;
		}
		return super.haveEqualFeature(eObject1, eObject2, feature);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.emf.ecore.util.EcoreUtil.EqualityHelper#haveEqualReference(org.eclipse.emf.ecore.EObject, org.eclipse.emf.ecore.EObject, org.eclipse.emf.ecore.EReference)
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected boolean haveEqualReference(EObject eObject1, EObject eObject2, EReference reference) {
		Object eO1value = eObject1.eGet(reference);
		Object eO2value = eObject2.eGet(reference);

		if (reference.isMany()) {
			List<EObject> eO1List = (List<EObject>) eO1value;
			List<EObject> eO2List = (List<EObject>) eO2value;
			if (reference.isOrdered() && reference.getEKeys().size() > 0) {
				Comparator<EObject> comparator = new EObjectComparator(reference.getEKeys());
				// Don't modify the original list!
				eO1List = new ArrayList<EObject>(eO1List);
				eO2List = new ArrayList<EObject>(eO2List);
				Collections.sort(eO1List, comparator);
				Collections.sort(eO2List, comparator);
			}
			return equals(eO1List, eO2List);
		} else {
			return equals((EObject)eO1value, (EObject)eO2value);
		}
	}

}
