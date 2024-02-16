/*
 * This file is part of bibliothek, licensed under the MIT License.
 *
 * Copyright (c) 2024 GeyserMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.papermc.bibliothek.util;

import java.net.FileNameMap;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

public class CustomFileNameMap implements FileNameMap {
  private final FileNameMap internalMap = URLConnection.getFileNameMap();

  private final Map<String, String> customMap = new HashMap<>() {{
    put("mcpack", "application/zip");
  }};

  public String getContentTypeFor(String fileName) {
    return customMap.getOrDefault(getExtension(fileName), internalMap.getContentTypeFor(fileName));
  }

  private String getExtension(String fileName) {
    int index = fileName.lastIndexOf('.');
    if (index == -1) {
      return "";
    }
    return fileName.substring(index + 1);
  }
}
