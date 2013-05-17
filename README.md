sbt-jsw-plugin
==============

SBT Java Service Wrapper Plugin

A Maven app-assembler-plugin like thingy for SBT


## Requirements
 sbt 0.12.x

## Installation

 sbt 0.12.x

 Build the plugin in and publish it locally.

 Add the following lines to plugins.sbt

> addSbtPlugin("hms.sbt.plugins" % "sbt-jsw-plugin" % "0.2.2")


Inject plugin settings into project in build.sbt:

> seq(SbtJswPlugin.jswPluginSettings :_*)

> jswMainClass := "org.xyz.Main"


## Usage

  > ./sbt dist

  > dist will create a Java Service Wrapper App at target/scala-<version>/<project-name>-<version>
