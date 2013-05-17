
seq(SbtJswPlugin.jswPluginSettings :_*)

organization := "org.xyz.Main"

name := "xyz-app"

version := "1.0"

scalaVersion := "2.10.0"

jswMainClass := "org.xyz.Main"

jswInitialHeapSizeInMB := 256

jswMaxHeapSizeInMB := 512

jswSupportedPlatforms := Seq(
  "linux-x86-32", "linux-x86-64", "windows-x86-32"
)

additionalLibs in Dist <<= (additionalLibs in Dist, baseDirectory) { (addionalLibs, base) => addionalLibs ++ Seq(base / "native") }