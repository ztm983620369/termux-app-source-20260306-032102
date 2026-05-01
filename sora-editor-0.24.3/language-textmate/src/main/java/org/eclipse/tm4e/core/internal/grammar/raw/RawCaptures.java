/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.core.internal.grammar.raw;

import java.util.ArrayList;

import org.eclipse.tm4e.core.internal.parser.PropertySettable;

public final class RawCaptures extends PropertySettable.HashMap<Object> implements IRawCaptures {

	private static final long serialVersionUID = 1L;

	@Override
	public IRawRule getCapture(final String captureId) {
		final Object obj = get(captureId);
		return obj instanceof final IRawRule rawRule ? rawRule : null;
	}

	@Override
	public Iterable<String> getCaptureIds() {
		final var ids = new ArrayList<String>();
		for (final var entry : entrySet()) {
			if (entry.getValue() instanceof IRawRule) {
				ids.add(entry.getKey());
			}
		}
		return ids;
	}
}
