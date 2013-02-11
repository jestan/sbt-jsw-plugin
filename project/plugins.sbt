resolvers += "SBT Idea plugin repository" at "http://mpeltonen.github.com/maven/"

resolvers += Resolver.url("Typesafe repository", new java.net.URL("http://repo.typesafe.com/typesafe/releases/"))(Resolver.defaultIvyPatterns)

libraryDependencies <+= sbtVersion("org.scala-sbt" % "scripted-plugin" % _)

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.2.0")
