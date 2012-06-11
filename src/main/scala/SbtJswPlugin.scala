import sbt._
import Keys._
import Load.BuildStructure
import classpath.ClasspathUtilities
import Project.Initialize
import CommandSupport._


import org.skife.tar.Tar
import java.io.File

object SbtJswPlugin extends Plugin {

  case class JswConfig(distName: String,
                       distVersion: String,
                       configSourceDirs: Seq[File],
                       jswMainClass: String,
                       libFilter: File ⇒ Boolean,
                       additionalLibs: Seq[File])

  val jswDeltaPackName = "wrapper-delta-pack"
  val jswDeltaPackVersion = "3.3.6"

  val jswDeltaPack = "tanukisoft" % jswDeltaPackName % "3.3.6" % "dist" artifacts (Artifact(jswDeltaPackName, "tar.gz", "tar.gz"))

  val Dist = config("dist") extend (Runtime)

  val dist = TaskKey[File]("dist", "Create JSW App")
  val distClean = TaskKey[Unit]("dist-clean", "Removes the JSW App directory")

  val configSourceDirs = TaskKey[Seq[File]]("conf-source-directories","Configuration files are copied from these directories")
  val jswMainClass = SettingKey[String]("jsw-main-class", "App main class to use in start script")


  val jswJvmOptions = SettingKey[String]("jsw-jvm-options", "JVM parameters to use in start script")

  val libFilter = SettingKey[File ⇒ Boolean]("lib-filter", "Filter of dependency jar files")
  val additionalLibs = TaskKey[Seq[File]]("additional-libs", "Additional dependency jar files")
  val distConfig = TaskKey[JswConfig]("dist-config")

  val jswDepsFilter: NameFilter = (s: String) => s.startsWith("wrapper-delta-pack")

  val distNeedsPackageBin = dist <<= dist.dependsOn(packageBin in Compile)

  lazy val jswPluginSettings: Seq[sbt.Project.Setting[_]] =

    inConfig(Dist)(
      Seq(
        dist <<= packageBin,
        packageBin <<= distTask,
        distClean <<= distCleanTask,
        dependencyClasspath <<= (dependencyClasspath in Runtime),
        unmanagedResourceDirectories <<= (unmanagedResourceDirectories in Runtime),
        configSourceDirs <<= defaultConfigSourceDirs,
        libFilter := {
          f ⇒ true
        },
        additionalLibs <<= defaultAdditionalLibs,
        distConfig <<= (name, version, configSourceDirs, jswMainClass, libFilter, additionalLibs) map JswConfig
      )
    ) ++
      Seq(
        libraryDependencies <<= libraryDependencies apply {deps => deps :+ jswDeltaPack},
        ivyConfigurations ++= Seq(Dist),
        dist <<= (dist in Dist),
        distNeedsPackageBin)

  private def distTask: Initialize[Task[File]] =
    (distConfig, crossTarget, update, dependencyClasspath, projectDependencies, allDependencies, buildStructure, state) map {
      (conf, tgt, updateResults, cp, projDeps, allDeps, buildStruct, st) =>

        val log = logger(st)

        val outputDir = tgt / (conf.distName + "-" + conf.distVersion)
        val distBinPath = outputDir / "bin"
        val distConfigPath = outputDir / "conf"
        val distLibPath = outputDir / "lib"
        val distLogPath = outputDir / "logs"

        val subProjectDependencies: Set[SubProjectInfo] = allSubProjectDependencies(projDeps, buildStruct, st)

        log.info("Starting to create JSW distribution at %s ..." format outputDir)

        val jswExtractionDir = IO.createTemporaryDirectory

        log.info("Unpacked JSW resources to %s ..." format jswExtractionDir)
        unpackJswDeltaPack(jswExtractionDir, updateResults)

        val jswDir = jswExtractionDir / (jswDeltaPackName + "-" + jswDeltaPackVersion)


        //Create Java Service Wrapper App layout

        IO.createDirectory(outputDir)


        //Copy jsw scripts to bin
        copyWrapperScripts(
          Map(
            conf.distName -> jswDir / "bin" / "testwrapper",
            (conf.distName + ".bat") -> jswDir / "bin" / "TestWrapper.bat"), distBinPath)

        //Copy all jsw exec deps to bin dir
        copyFiles(IO.listFiles(jswDir / "bin"), distBinPath, (f: File) => f.name.startsWith("wrapper-"), true)

        //Copy all app conf dir
        copyDirectories(conf.configSourceDirs, distConfigPath)

        //Make logs/wrapper.log file by copying jsw default logs dir
        copyDirectories(Seq(jswDir / "logs"), distLogPath)

        //Copy the app's jar to lib dir
        copyJars(tgt, distLibPath)

        //Copy all jsw jars and platform specific binaries
        copyFiles(IO.listFiles(jswDir / "lib"), distLibPath, (f: File) => !f.name.contains("wrappertest.jar"))

        //Copy all transitive dependencies of the project
        copyFiles(libFiles(cp, conf.libFilter), distLibPath)

        //Copy additional jars to lib dir
        copyFiles(conf.additionalLibs, distLibPath)

        for (subTarget <- subProjectDependencies.map(_.target)) {
          copyJars(subTarget, distLibPath)
        }

        val allLibJars = IO.listFiles(distLibPath).toList.filter(_.ext == "jar")

        val jswLibPaths = ((2 to (allLibJars.length + 2)) zip allLibJars).map{case (i, f) => "wrapper.java.classpath.%d=%s" format(i, f.name)}


        //Generate Java Service Wrapper exec scripts
        JswConf(conf.distName, conf.jswMainClass, jswLibPaths.mkString("\r\n")).writeToPath(distConfigPath)

        log.info("JSW distribution created.")

        outputDir
    }


  private def unpackJswDeltaPack(jswExtractionDir: File, updateResults: Types.Id[UpdateReport]) {
    val jswTarGzPath = (updateResults matching artifactFilter(name = jswDepsFilter)).map(_.getAbsolutePath).headOption.getOrElse("")

    assert(jswTarGzPath.isEmpty == false, jswDeltaPackName + "-" + jswDeltaPackVersion + ".tar.gz not found")
    Tar.extractFiles(new File(jswTarGzPath), jswExtractionDir)
  }

  private def distCleanTask: Initialize[Task[Unit]] =
    (distConfig, crossTarget, allDependencies, streams) map {
      (conf, tgt, deps, s) ⇒

        val log = s.log
        val outDir = tgt / (conf.distName + "-" + conf.distVersion)
        log.info("Cleaning " + outDir)
        IO.delete(outDir)
    }

  private def defaultConfigSourceDirs = (sourceDirectory, unmanagedResourceDirectories) map {
    (src, resources) ⇒
      Seq(src / "conf", src / "main" / "conf") ++ resources
  }

  private def defaultAdditionalLibs = (libraryDependencies) map {
    (libs) ⇒ Seq.empty[File]
  }

  private case class JswConf(appName: String, mainClass: String, libPaths: String) {

    def writeToPath(to: File) {
      val target = new File(to, "wrapper.conf")
      IO.write(target, confContent)
    }


    private def confContent =
      """
        |#********************************************************************
        |# Wrapper Java Properties
        |#********************************************************************
        |# Java Application
        |wrapper.java.command=java
        |
        |# Tell the Wrapper to log the full generated Java command line.
        |#wrapper.java.command.loglevel=INFO
        |
        |# Java Main class.  This class must implement the WrapperListener interface
        |#  or guarantee that the WrapperManager class is initialized.  Helper
        |#  classes are provided to do this for you.  See the Integration section
        |#  of the documentation for details.
        |wrapper.java.mainclass=org.tanukisoftware.wrapper.WrapperSimpleApp
        |
        |# Java Classpath (include wrapper.jar)  Add class path elements as
        |#  needed starting from 1
        |wrapper.java.classpath.1=../lib/wrapper.jar
        |%s
        |# Java Library Path (location of Wrapper.DLL or libwrapper.so)
        |wrapper.java.library.path.1=../lib
        |
        |# Java Bits.  On applicable platforms, tells the JVM to run in 32 or 64-bit mode.
        |wrapper.java.additional.auto_bits=TRUE
        |
        |# Java Additional Parameters
        |#wrapper.java.additional.1=
        |
        |# Initial Java Heap Size (in MB)
        |#wrapper.java.initmemory=3
        |
        |# Maximum Java Heap Size (in MB)
        |#wrapper.java.maxmemory=64
        |
        |# Application parameters.  Add parameters as needed starting from 1
        |wrapper.app.parameter.1=%s
        |
        |#********************************************************************
        |# Wrapper Logging Properties
        |#********************************************************************
        |# Enables Debug output from the Wrapper.
        |# wrapper.debug=TRUE
        |
        |# Format of output for the console.  (See docs for formats)
        |wrapper.console.format=PM
        |
        |# Log Level for console output.  (See docs for log levels)
        |wrapper.console.loglevel=INFO
        |
        |# Log file to use for wrapper output logging.
        |wrapper.logfile=../logs/wrapper.log
        |
        |# Format of output for the log file.  (See docs for formats)
        |wrapper.logfile.format=LPTM
        |
        |# Log Level for log file output.  (See docs for log levels)
        |wrapper.logfile.loglevel=INFO
        |
        |# Maximum size that the log file will be allowed to grow to before
        |#  the log is rolled. Size is specified in bytes.  The default value
        |#  of 0, disables log rolling.  May abbreviate with the 'k' (kb) or
        |#  'm' (mb) suffix.  For example: 10m = 10 megabytes.
        |wrapper.logfile.maxsize=0
        |
        |# Maximum number of rolled log files which will be allowed before old
        |#  files are deleted.  The default value of 0 implies no limit.
        |wrapper.logfile.maxfiles=0
        |
        |# Log Level for sys/event log output.  (See docs for log levels)
        |wrapper.syslog.loglevel=NONE
        |
        |#********************************************************************
        |# Wrapper General Properties
        |#********************************************************************
        |# Allow for the use of non-contiguous numbered properties
        |wrapper.ignore_sequence_gaps=TRUE
        |
        |# Title to use when running as a console
        |wrapper.console.title=%s
        |
        |#********************************************************************
        |# Wrapper Windows NT/2000/XP Service Properties
        |#********************************************************************
        |# WARNING - Do not modify any of these properties when an application
        |#  using this configuration file has been installed as a service.
        |#  Please uninstall the service before modifying this section.  The
        |#  service can then be reinstalled.
        |
        |# Name of the service
        |wrapper.name=%s
        |
        |# Display name of the service
        |wrapper.displayname=%s
        |
        |# Description of the service
        |wrapper.description=%s
        |
        |# Service dependencies.  Add dependencies as needed starting from 1
        |wrapper.ntservice.dependency.1=
        |
        |# Mode in which the service is installed.  AUTO_START or DEMAND_START
        |wrapper.ntservice.starttype=AUTO_START
        |
        |# Allow the service to interact with the desktop.
        |wrapper.ntservice.interactive=false
      """.stripMargin.format(libPaths, mainClass, appName, appName, appName, appName)

  }


  private def copyWrapperScripts(scripts: Map[String, File], toDir: File) {
    for ((name, sc) <- scripts) {
      val targetFile = new File(toDir, name)
      IO.copyFile(sc, targetFile)
      setExecutable(targetFile, true)
    }

  }

  private def copyFiles(files: Seq[File], toDir: File, filter: File => Boolean = (File) => true, setExec: Boolean = false) {
    for (f <- files if filter(f)) {
      val targetFile = new File(toDir, f.getName)
      IO.copyFile(f, targetFile)
      if (setExec) setExecutable(targetFile, true)
    }
  }


  private def setExecutable(target: File, executable: Boolean): Option[String] = {
    val success = target.setExecutable(executable, false)
    if (success) None else Some("Couldn't set permissions of " + target)
  }

  private def copyDirectories(fromDirs: Seq[File], to: File) {
    IO.createDirectory(to)
    for (from ← fromDirs) {
      IO.copyDirectory(from, to)
    }
  }

  private def copyJars(fromDir: File, toDir: File) {
    val jarFiles = fromDir.listFiles.filter(f =>
      f.isFile &&
        f.name.endsWith(".jar") &&
        !f.name.contains("-sources") &&
        !f.name.contains("-docs"))

    copyFiles(jarFiles, toDir)
  }

  private def libFiles(classpath: Classpath, libFilter: File ⇒ Boolean): Seq[File] = {
    val (libs, directories) = classpath.map(_.data).partition(ClasspathUtilities.isArchive)
    libs.map(_.asFile).filter(libFilter)
  }

  private def allSubProjectDependencies(projDeps: Seq[ModuleID], buildStruct: BuildStructure, state: State): Set[SubProjectInfo] = {
    val buildUnit = buildStruct.units(buildStruct.root)
    val uri = buildStruct.root
    val allProjects = buildUnit.defined.map {
      case (id, proj) => (ProjectRef(uri, id) -> proj)
    }

    val projDepsNames = projDeps.map(_.name)
    def include(project: ResolvedProject): Boolean = projDepsNames.exists(_ == project.id)
    val subProjects: Seq[SubProjectInfo] = allProjects.collect {
      case (projRef, project) if include(project) => projectInfo(projRef, project, buildStruct, state, allProjects)
    }.toList

    val allSubProjects = subProjects.map(_.recursiveSubProjects).flatten.toSet
    allSubProjects
  }

  private def projectInfo(projectRef: ProjectRef, project: ResolvedProject, buildStruct: BuildStructure, state: State,
                          allProjects: Map[ProjectRef, ResolvedProject]): SubProjectInfo = {

    def optionalSetting[A](key: SettingKey[A]) = key in projectRef get buildStruct.data

    def setting[A](key: SettingKey[A], errorMessage: => String) = {
      optionalSetting(key) getOrElse {
        logger(state).error(errorMessage);
        throw new IllegalArgumentException()
      }
    }

    def evaluateTask[T](taskKey: sbt.Project.ScopedKey[sbt.Task[T]]) = {
      EvaluateTask.apply(buildStruct, taskKey, state, projectRef)
    }

    val projDeps: Seq[ModuleID] = evaluateTask(Keys.projectDependencies) match {
      case Some((_, Value(moduleIds))) => moduleIds
      case _ => Seq.empty
    }

    val projDepsNames = projDeps.map(_.name)
    def include(project: ResolvedProject): Boolean = projDepsNames.exists(_ == project.id)
    val subProjects = allProjects.collect {
      case (projRef, proj) if include(proj) => projectInfo(projRef, proj, buildStruct, state, allProjects)
    }.toList

    val target = setting(Keys.crossTarget, "Missing crossTarget directory")
    SubProjectInfo(project.id, target, subProjects)
  }

  private case class SubProjectInfo(id: String, target: File, subProjects: Seq[SubProjectInfo]) {

    def recursiveSubProjects: Set[SubProjectInfo] = {
      val flatSubProjects = for {
        x <- subProjects
        y <- x.recursiveSubProjects
      } yield y

      flatSubProjects.toSet + this
    }

  }

}

