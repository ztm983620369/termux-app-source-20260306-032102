/**
 * Copyright (c) 2023 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke (Vegard IT) - initial implementation
 */
package org.eclipse.tm4e.core.internal.parser;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.tm4e.core.internal.grammar.raw.RawCaptures;
import org.eclipse.tm4e.core.internal.grammar.raw.RawRepository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

public class TMParserJSON implements TMParser {

	public static final TMParserJSON INSTANCE = new TMParserJSON();

    private static final Gson LOADER = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .create();

	protected Map<String, Object> loadRaw(final Reader source) {
        final var rawText = readAll(source);
        final var sanitized = sanitizeJsonc(rawText);
		return LOADER.fromJson(new StringReader(sanitized), Map.class);
	}

    private static String readAll(final Reader reader) {
        try {
            final var sb = new StringBuilder();
            final var buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sanitizeJsonc(final String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        final var src = input.charAt(0) == '\uFEFF' ? input.substring(1) : input;
        final var out = new StringBuilder(src.length());

        boolean inString = false;
        boolean escape = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            final char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }

            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }

            if (c == ',') {
                int j = i + 1;
                while (j < src.length()) {
                    final char cj = src.charAt(j);
                    if (!Character.isWhitespace(cj)) {
                        break;
                    }
                    j++;
                }
                if (j < src.length()) {
                    final char cj = src.charAt(j);
                    if (cj == '}' || cj == ']') {
                        continue;
                    }
                }
            }

            out.append(c);
        }

        return out.toString();
    }

	@Override
	public final <T extends PropertySettable<?>> T parse(final Reader source, final ObjectFactory<T> factory) {
		final Map<String, Object> rawRoot = loadRaw(source);
		return transform(rawRoot, factory);
	}

	private <T extends PropertySettable<?>> T transform(final Map<String, Object> rawRoot, final ObjectFactory<T> factory) {
		final var root = factory.createRoot();
		final var path = new TMParserPropertyPath();

		for (final var e : rawRoot.entrySet()) {
			addChild(factory, path, root, e.getKey(), e.getValue());
		}
		return root;
	}

	/**
	 * @param propertyId String | Integer
	 */
	private <T extends PropertySettable<?>> void addChild(final ObjectFactory<T> handler, final TMParserPropertyPath path,
			final PropertySettable<?> parent, final Object propertyId, final Object rawChild) {
		path.add(propertyId);
		if (rawChild instanceof final Map<?, ?> map) {
			final var transformedChild = handler.createChild(path, Map.class);
			for (final Map.Entry<@NonNull ?, @NonNull ?> e : map.entrySet()) {
				addChild(handler, path, transformedChild, e.getKey(), e.getValue());
			}
			setProperty(parent, propertyId, transformedChild);
		} else if (rawChild instanceof final List list) {
			final var transformedChild = handler.createChild(path, List.class);
			for (int i = 0, l = list.size(); i < l; i++) {
				addChild(handler, path, transformedChild, i, list.get(i));
			}
			setProperty(parent, propertyId, transformedChild);
		} else {
			if (!(parent instanceof RawRepository) && !(parent instanceof RawCaptures)) {
				setProperty(parent, propertyId, rawChild);
			}
		}
		path.removeLastElement();
	}

	/**
	 * @param propertyId String | Integer
	 */
	@SuppressWarnings("unchecked")
	private void setProperty(final PropertySettable<?> settable, final Object propertyId, final Object value) {
		((PropertySettable<Object>) settable).setProperty(propertyId.toString(), value);
	}
}
