/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.betterdocs.indexer

import java.util.regex.Pattern

import com.betterdocs.configuration.BetterDocsConfig
import com.betterdocs.crawler.Repository

import scala.collection.mutable
import scala.collection.immutable
import scala.util.Try


case class IndexEntry(file: String, tokens: Set[Token], score: Int)

/* Since our tokens are fully qualified import names. */
case class Token(importName: String, lineNumbers: immutable.SortedSet[Int])

trait BasicIndexer extends Serializable {

  /** Adjusting the lines of context is crucial to the kinds of token generated. (Tune this.) */
  def linesOfContext: Int

  /** Import pattern of some languages is similar. */
  val importPattern: Pattern = Pattern.compile("import (.*)\\.(\\w+);")

  def generateTokens(files: Map[String, String], excludePackages: List[String],
      repo: Option[Repository]): Set[IndexEntry]

  protected def mapToTokens(map: mutable.Map[String, immutable.SortedSet[Int]]): Set[Token] = {
    map.map{ case (key, value) => Token(key, value) }.toSet
  }
}

class JavaFileIndexer extends BasicIndexer {

  /** For Java code based on trial and error 10 to 20 seems good. */
  override def linesOfContext: Int = BetterDocsConfig.linesOfContext.toInt

  override def generateTokens(files: Map[String, String], excludePackages: List[String],
    repo: Option[Repository]): Set[IndexEntry] = {
    var tokens = immutable.HashSet[IndexEntry]()
    val r = repo.getOrElse(Repository.empty)
    for (file <- files) {
      val (fileName, fileContent) = file
      val tokenMap = new mutable.HashMap[String, immutable.SortedSet[Int]]
      val imports = extractImports(fileContent, excludePackages)
      generateTokensWRTImports(imports, fileContent).map { x =>
       x.map { y =>
          val FQCN = y._2._1 + "." + y._2._2
          val oldValue: immutable.SortedSet[Int] = tokenMap.getOrElse(FQCN, immutable.SortedSet())
          // This map can be used to create one to N index if required.
          tokenMap += ((FQCN, oldValue ++ List(y._1)))
        }
        val (_, actualFileName) = fileName.splitAt(fileName.indexOf('/'))
          val fullGithubURL =
            s"""http://github.com/${r.login}/${r.name}/blob/${r.defaultBranch}$actualFileName"""
        tokens = tokens + IndexEntry(fullGithubURL, mapToTokens(tokenMap), r.stargazersCount)
        //  tokens = tokens ++ List(Token(fullGithubURL, x.map(z => z._2._1 + "." + z._2
        //  ._2), x.head._1, score))
        tokenMap.clear()
      }
    }
    tokens
  }

  private def extractImports(java: String, packages: List[String]) = java.split("\n")
    .filter(x => x.startsWith("import"))
    .map(x => importPattern.matcher(x)).filter(_.find)
    .flatMap(x => Try((x.group(1),  x.group(2).trim)).toOption)
    .filterNot { case (left, right) => packages.contains(left) }.toSet

  /**
   * Takes a line of code and cleans it for further indexing.
   */
  private def cleanUpCode(line: String): String = {
   val cleaned = line.replaceFirst("""\s*(import|private|public|protected|\/?\*|//).*""", "")
      .replaceAll("\\W+", " ")
    if(!cleaned.isEmpty) " " + cleaned.toLowerCase + " " else ""
  }

  private def generateTokensWRTImports(imports: Set[(String, String)],
      java: String): List[Array[(Int, (String, String))]] = {
    val lines = java.split("\n").map(cleanUpCode)
    (lines.sliding(linesOfContext) zip (1 to lines.size).sliding(linesOfContext)).toList
      .map { case (linesWindow, lineNumbersWindow) =>
      (linesWindow zip lineNumbersWindow).flatMap { case (line, lineNumber) if !line.isEmpty =>
        val l = line.split(" ").distinct
        imports.map(y => (l.contains(y._2.toLowerCase), lineNumber, y))
          .filter(_._1).map(a => (a._2, a._3))
      case _ => Set[(Int, (String, String))]()
      }
    }.distinct.filter(_.nonEmpty)
  }
}
