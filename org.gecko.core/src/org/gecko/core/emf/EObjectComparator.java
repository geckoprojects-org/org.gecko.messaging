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

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;

/**
 * Comparator to sort after the given list of {@link EAttribute}
 * @author Mark Hoffmann
 * @since 26.03.2019
 */
public class EObjectComparator implements Comparator<EObject> {

	private final List<EAttribute> keyList  = new LinkedList<EAttribute>();

	public EObjectComparator() {
	}

	public EObjectComparator(List<EAttribute> keys) {
		if (keys != null) {
			keyList.addAll(keys);
		}
	}

	public EObjectComparator(EAttribute key) {
		if (key != null) {
			keyList.add(key);
		}
	}

	/* (non-Javadoc)
	 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
	 */
	@Override
	public int compare(EObject o1, EObject o2) {
		if (o1 == null && o2 != null) {
			return -1;
		} else if (o1 != null && o2 == null) {
			return 1;
		} else if (o1 == null && o2 == null) {
			return 0;
		}
		Integer result = null;
		for (EAttribute key : keyList) {
			int compResult = compareWithKey(o1, o2, key);
			if (compResult != 0) {
				return compResult;
			} else {
				result = Integer.valueOf(compResult);
			}
		}
		return result == null ? doCompare(o1, o2) : result.intValue();
	}

	/**
	 * Compares the two objects using the given key
	 * @param object1 the first object
	 * @param object2 the second object
	 * @param key the key attribute
	 * @return -1 if one is lower that two, 0 if both are equals, and 1 if one is larger that two
	 */
	private int compareWithKey(EObject object1, EObject object2, EAttribute key) {
		if (key == null) {
			return doCompare(object1, object2);
		}
		Object keyObject1 = object1.eGet(key);
		Object keyObject2 = object2.eGet(key);
		return doCompare(keyObject1, keyObject2);
	}

	/**
	 * Executes a compare 
	 * @param object1 object one
	 * @param object2 object two
	 * @return -1 if one is lower that two, 0 if both are equals, and 1 if one is larger that two
	 */
	@SuppressWarnings("unchecked")
	private int doCompare(Object object1, Object object2) {
		if (object1 == null && object2 != null) {
			return -1;
		}
		if (object1 == null && object2 == null) {
			return 0;
		}
		if (object1 != null && object2 == null) {
			return 1;
		}
		if (object1 instanceof Comparable<?>) {
			Comparable<Object> comp1 = (Comparable<Object>) object1;
			return comp1.compareTo(object2);
		} else {
			return object1.toString().compareTo(object2.toString());
		}
	}

}
