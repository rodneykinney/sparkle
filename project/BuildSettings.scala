/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

import sbt._
import sbt.Keys._

import sbtrelease.ReleasePlugin
import spray.revolver.RevolverPlugin._

import com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseKeys
import com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseCreateSrc

import sbtassembly.Plugin.AssemblyKeys._
import sbtassembly.Plugin._

import BackgroundServiceKeys._

object BuildSettings {

  lazy val allSettings =
    orgSettings ++
    compileSettings ++
    eclipseSettings ++
    itSettingsWithEclipse ++
    slf4jSettings ++
    testSettings ++
    publishSettings ++
    dependencyOverrideSettings ++
    org.scalastyle.sbt.ScalastylePlugin.Settings

  lazy val orgSettings = Seq(
    organization := "nest",
    licenses += ("Apache-2.0", url("http://apache.org/licenses/LICENSE-2.0.html"))
  )

  lazy val compileSettings = Seq(
    scalaVersion := Dependencies.V.scala,
    scalaBinaryVersion := "2.10",
    testOptions += Tests.Argument("-oF"), // show full stack traces during test failures
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-language:postfixOps", "-target:jvm-1.7")
  )

  lazy val eclipseSettings = Seq(
    EclipseKeys.withSource := true,
    EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource,
    EclipseKeys.eclipseOutput := Some("eclipse-target")
//     EclipseKeys.withBundledScalaContainers := false // LATER for eclipse 4.4
  )

  lazy val itSettingsWithEclipse = Defaults.itSettings ++ Seq(
    // include integration test code (src/it) in generated eclipse projects
    EclipseKeys.configurations := Set(sbt.Compile, sbt.Test, sbt.IntegrationTest)
  )

  lazy val testSettings = Seq(
    parallelExecution in test in IntegrationTest := false // cassandra driver (2.0.[01]) seems to have trouble with multiple keyspaces..
  //    fork in IntegrationTest := true // LATER clean up tests better so that this is unnecessary
  )

  lazy val slf4jSettings = Seq(
    // see http://stackoverflow.com/questions/7898273/how-to-get-logging-working-in-scala-unit-tests-with-testng-slf4s-and-logback
    testOptions += Tests.Setup(cl =>
      cl.loadClass("org.slf4j.LoggerFactory").
        getMethod("getLogger", cl.loadClass("java.lang.String")).
        invoke(null, "ROOT")
    )
  )
  
  lazy val sparkleAssemblySettings = assemblySettings ++ Seq(
    defaultMergeStrategy
  )

  lazy val defaultMergeStrategy =
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old => {
      case "META-INF/io.netty.versions.properties" => MergeStrategy.first
      case x => old(x)
    }
  }

  lazy val publishSettings =
    // bintraySettings ++ // disabled pending https://github.com/softprops/bintray-sbt/issues/18
    sbtassembly.Plugin.assemblySettings ++
      ReleasePlugin.releaseSettings ++
      MavenPublish.settings ++
      Revolver.settings  // TODO Revolver isn't really 'publish' settings, and BackgroundService includes revolver
  
  lazy val dependencyOverrideSettings = Seq(
    dependencyOverrides ++= Dependencies.dependencyOverrides
  )

  /** settings so that we launch our favorite Main by default */
  def setMainClass(className: String): Seq[Setting[_]] = {
    Seq(
      mainClass in Revolver.reStart := Some(className),
      mainClass in assembly := Some(className),
      mainClass in (Compile, run):= Some(className)
    )
  }
}
