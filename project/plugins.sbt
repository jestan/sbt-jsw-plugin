
resolvers += Resolver.url("Typesafe repository", new java.net.URL("http://repo.typesafe.com/typesafe/releases/"))(Resolver.defaultIvyPatterns)

libraryDependencies <+= (sbtVersion) { sv =>
  "org.scala-sbt" % "scripted-plugin" % sv
}

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.2.0")
