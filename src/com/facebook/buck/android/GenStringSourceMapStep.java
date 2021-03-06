/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.XmlDomParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * This class implements a Buck build step that will generate a JSON file with the following info
 * for each <string>, <plurals> and <string-array> resource found in the strings.xml files for each
 * resource directory:
 * <p/>
 * <pre>
 * android_resource_name : {
 *   androidResourceId,
 *   stringsXmlPath
 * }
 * </pre>
 * <p/>
 * where:
 * <ul>
 *   <li>androidResourceId is the integer value, assigned by aapt, extracted from R.txt
 *   <li>stringsXmlPath is the path to the first strings.xml file where this string resource was
 *   found.
 * </ul>
 * <p/>
 * Example:
 * <pre>
 * {
 *   "strings":{
 *     "thread_list_new_message_button":{
 *       "androidResourceId":"0x7f0800e6",
 *       "stringsXmlPath":"android_res/com/facebook/messaging/res/values/strings.xml"
 *     },
 *     "bug_report_category_lock_screen":{
 *       "androidResourceId":"0x7f080489",
 *       "stringsXmlPath":"android_res/com/facebook/bugreporter/res/values/strings.xml"
 *     }
 *   }
 * }
 * </pre>
 */
public class GenStringSourceMapStep extends AbstractExecutionStep {

  private final Path rDotJavaSrcDir;
  private final Set<Path> resDirectories;
  private final Path destinationPath;

  private HashMap<String, Integer> mapResNameToResId = Maps.newHashMap();

  /**
   * Associates each string resource with it's integer id (as assigned by {@code aapt} during
   * GenRDotJavaStep) and it's originating strings.xml file (path).
   *
   * @param rDotJavaSrcDir Directory where {@code R.txt} is found
   * @param resDirectories Directories of resource files. This the same list, with same ordering
   *     that was provided to {@code aapt} during GenRDotJavaStep.
   * @param destinationPath Directory where where {@code strings.json} is written to.
   */
  public GenStringSourceMapStep(
      Path rDotJavaSrcDir,
      Set<Path> resDirectories,
      Path destinationPath) {
    super("build_string_source_map");
    this.rDotJavaSrcDir = Preconditions.checkNotNull(rDotJavaSrcDir);
    this.resDirectories = ImmutableSet.copyOf(resDirectories);
    this.destinationPath = Preconditions.checkNotNull(destinationPath);
  }

  @Override
  public int execute(ExecutionContext context) {
    // Read the R.txt file that was generated by aapt during GenRDotJavaStep.
    // This file contains all the resource names and the integer id that aapt assigned.
    Path rDotTxtPath = rDotJavaSrcDir.resolve("R.txt");
    try {
      ProjectFilesystem filesystem = context.getProjectFilesystem();
      CompileStringsStep.buildResourceNameToIdMap(filesystem, rDotTxtPath, mapResNameToResId);
    } catch (FileNotFoundException ex) {
      context.logError(ex,
        "The '%s' file is not present.",
        rDotTxtPath);
      return 1;
    } catch (IOException ex) {
      context.logError(ex, "Failure parsing R.txt file.");
      return 1;
    }

    Map<String, NativeStringInfo> nativeStrings = parseStringFiles(context);

    // write nativeStrings out to a file
    Path outputPath = destinationPath.resolve("strings.json");
    try {
      ObjectMapper mapper = new ObjectMapper();
      ProjectFilesystem filesystem = context.getProjectFilesystem();
      mapper.writeValue(filesystem.getFileForRelativePath(outputPath), nativeStrings);
    } catch (IOException ex) {
      context.logError(
          ex, "Failed when trying to save the output file: '%s'", outputPath.toString());
      return 1;
    }

    return 0; // success
  }

  private Map<String, NativeStringInfo> parseStringFiles(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    Map<String, NativeStringInfo> nativeStrings = Maps.newHashMap();

    for (Path resDir : resDirectories) {
      Path stringsPath = resDir.resolve("values").resolve("strings.xml");
      File stringsFile = filesystem.getFileForRelativePath(stringsPath);
      if (stringsFile.exists()) {
        try {
          Document dom = XmlDomParser.parse(stringsFile);

          NodeList stringNodes = dom.getElementsByTagName("string");
          scrapeNodes(stringNodes, stringsPath.toString(), nativeStrings);

          NodeList pluralNodes = dom.getElementsByTagName("plurals");
          scrapeNodes(pluralNodes, stringsPath.toString(), nativeStrings);

          NodeList arrayNodes = dom.getElementsByTagName("string-array");
          scrapeNodes(arrayNodes, stringsPath.toString(), nativeStrings);
        } catch (IOException ex) {
          context.logError(
              ex,
              "Failed to parse strings file: '%s'",
              stringsPath);
        }
      }
    }

    return nativeStrings;
  }

  /**
   * Scrapes string resource names and values from the list of xml nodes passed and populates
   * {@code stringsMap}, ignoring resource names that are already present in the map.
   *
   * @param nodes A list of XML nodes.
   * @param nativeStrings Collection of native strings, only new ones will be added to it.
   */
  @VisibleForTesting
  void scrapeNodes(
      NodeList nodes,
      String stringsFilePath,
      Map<String, NativeStringInfo> nativeStrings) {
    for (int i = 0; i < nodes.getLength(); ++i) {
      Node node = nodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      if (!mapResNameToResId.containsKey(resourceName)) {
        continue;
      }
      int resourceId = mapResNameToResId.get(resourceName);
      // Add only new resources (don't overwrite existing ones)
      if (!nativeStrings.containsKey(resourceName)) {
        nativeStrings.put(resourceName, new NativeStringInfo(resourceId, stringsFilePath));
      }
    }
  }

  /**
   * This class manages the attributes for a <string> resource that we obtain from parsing
   * the various strings.xml files. As information is cross-referenced with other sources, the
   * combined set of knowledge for each string is kept here. This class is serialized to JSON for
   * the final output file.
   */
  private static class NativeStringInfo {
    private String androidResourceId; // assigned by aapt, we got it from R.txt
    private String stringsXmlPath;    // relative path to the strings.xml where this
    // resource originated from

    public NativeStringInfo(Integer androidResourceId, String stringsXmlPath) {
      this.androidResourceId =
          String.format("0x%08X", Preconditions.checkNotNull(androidResourceId));
      this.stringsXmlPath = Preconditions.checkNotNull(stringsXmlPath);
    }

    @SuppressWarnings("unused") // Used via reflection for JSON serialization.
    public String getAndroidResourceId() {
      return androidResourceId;
    }

    @SuppressWarnings("unused") // Used via reflection for JSON serialization.
    public String getStringsXmlPath() {
      return stringsXmlPath;
    }
  }
}
