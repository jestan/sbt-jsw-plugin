seq(ScriptedPlugin.scriptedSettings: _*)

sbtPlugin := true

organization := "hms.sbt.plugins"

name := "sbt-jsw-plugin"

version := "0.2.2"

scalacOptions += "-deprecation"

scalacOptions += "-unchecked"

publishMavenStyle := true

libraryDependencies += "org.skife.tar" % "java-gnu-tar" % "0.0.1"

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials-release")

publishTo <<= (version) { version: String =>
  val repo = "http://192.168.0.7:8080/archiva/repository/"
  if (version.trim.endsWith("SNAPSHOT"))
    Some("Repository Archiva Managed snapshots Repository" at repo + "snapshots/")
  else
    Some("Repository Archiva Managed internal Repository" at repo + "internal/")
}
