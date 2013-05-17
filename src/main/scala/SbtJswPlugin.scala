import sbt._
import Keys._
import Load.BuildStructure
import classpath.ClasspathUtilities
import Project.Initialize


import org.skife.tar.Tar
import java.io.File

object SbtJswPlugin extends Plugin {

  case class JswConfig(distName: String,
                       distVersion: String,
                       configSourceDirs: Seq[File],
                       jswMainClass: String,
                       initHeapInMB: Int,
                       maxHeapInMB: Int,
                       supportedPlatforms: Seq[String],
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

  val jswInitialHeapSizeInMB = SettingKey[Int]("jsw-init-memory-options", "JVM initial heap size in MB")
  val jswMaxHeapSizeInMB = SettingKey[Int]("jsw-max-memory-options", "JVM max heap size in MB")

  val jswSupportedPlatforms = SettingKey[Seq[String]]("jsw-supported-platforms-options", "JSW App supported Operating Systems")

  val libFilter = SettingKey[File ⇒ Boolean]("lib-filter", "Filter of dependency jar files")

  val additionalLibs = SettingKey[Seq[File]]("additional-libs", "Additional dependency files")

  val distConfig = TaskKey[JswConfig]("dist-config")

  val jswDepsFilter: NameFilter = (s: String) => s.startsWith("wrapper-delta-pack")

  val distNeedsPackageBin = dist <<= dist.dependsOn(packageBin in Compile)

  lazy val jswPluginSettings: Seq[sbt.Project.Setting[_]] = Seq(
        libFilter := {
          f ⇒ true
        },
        jswInitialHeapSizeInMB := 256,
        jswMaxHeapSizeInMB := 1024,
        jswSupportedPlatforms := Seq(
         "linux-x86-32", "linux-x86-64", "windows-x86-32"
        ),
        additionalLibs := Seq.empty[File]
    ) ++ inConfig(Dist)(
      Seq(
        dist <<= packageBin,
        packageBin <<= distTask,
        distClean <<= distCleanTask,
        dependencyClasspath <<= (dependencyClasspath in Runtime),
        unmanagedResourceDirectories <<= (unmanagedResourceDirectories in Runtime),
        configSourceDirs <<= defaultConfigSourceDirs,
        distConfig <<= (name, version, configSourceDirs, jswMainClass, jswInitialHeapSizeInMB, jswMaxHeapSizeInMB, jswSupportedPlatforms, libFilter, additionalLibs) map JswConfig
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

        val outputDir = tgt / (conf.distName + "-" + conf.distVersion)

        val distBinPath = outputDir / "bin"
        val distConfigPath = outputDir / "conf"
        val distLibPath = outputDir / "lib"
        val distLogPath = outputDir / "logs"

        val subProjectDependencies: Set[SubProjectInfo] = allSubProjectDependencies(projDeps, buildStruct, st)

        st.log.info("Starting to create JSW distribution at %s ..." format outputDir)

        val jswExtractionDir = IO.createTemporaryDirectory

        st.log.info("Unpacked JSW resources to %s ..." format jswExtractionDir)
        unpackJswDeltaPack(jswExtractionDir, updateResults)

        val jswDir = jswExtractionDir / (jswDeltaPackName + "-" + jswDeltaPackVersion)


        //Create Java Service Wrapper App layout

        IO.createDirectory(outputDir)


        //Copy jsw scripts to bin
        JswScript(conf.distName).writeToPath(distBinPath)

        //Copy all jsw exec deps to bin dir
        copyFiles(IO.listFiles(jswDir / "bin"), distBinPath, (f: File) => f.name.contains(conf.distName) || conf.supportedPlatforms.exists(s => f.name.contains(s)), true)

        //Copy all app conf dir
        copyDirectories(conf.configSourceDirs, distConfigPath)

        //Make logs/wrapper.log file by copying jsw default logs dir
        copyDirectories(Seq(jswDir / "logs"), distLogPath)

        //Copy the app's jar to lib dir
        copyJars(tgt, distLibPath)

        //Copy all jsw jars and platform specific binaries
        val libFileFilter = (f: File) => {
          if(f.name.contains("libwrapper-") || f.name.contains("wrapper-windows-")) {
            conf.supportedPlatforms.exists(s => f.name.contains(s))
          } else {
            !f.name.contains("wrappertest.jar")
          }
        }
        copyFiles(IO.listFiles(jswDir / "lib"), distLibPath, libFileFilter)

        //Copy all transitive dependencies of the project
        copyFiles(libFiles(cp, conf.libFilter), distLibPath)

        //Copy additional jars to lib dir
        copyFiles(conf.additionalLibs.map(IO.listFiles).flatten, distLibPath, conf.libFilter)

        for (subTarget <- subProjectDependencies.map(_.target)) {
          copyJars(subTarget, distLibPath)
        }

        val allLibJars = IO.listFiles(distLibPath).toList.filter(f => f.name != "wrapper.jar" && f.ext == "jar")

        val jswLibPaths = "wrapper.java.classpath.2=conf" :: ((3 to (allLibJars.length + 3)) zip allLibJars).map {
          case (i, f) => "wrapper.java.classpath." + i + "=%REPO_DIR%/" + f.name
        }.toList


        //Generate Java Service Wrapper exec scripts
        JswConf(conf.distName, conf.jswMainClass, jswLibPaths.mkString("\n"), conf.initHeapInMB, conf.maxHeapInMB).writeToPath(distConfigPath)

        st.log.info("JSW distribution created at %s." format outputDir)

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

  private case class JswScript(appName: String) {

    def writeToPath(to: File) {
      val shTarget = new File(to, appName)
      IO.write(shTarget, shScriptContent)
      setExecutable(shTarget, true)

      val batTarget = new File(to, appName + ".bat")
      IO.write(batTarget, batScriptContent)
      setExecutable(batTarget, true)
    }

    private def shScriptContent =
      """
        |#! /bin/sh
        |
        |#
        |# Copyright (c) 1999, 2006 Tanuki Software Inc.
        |#
        |# Java Service Wrapper sh script.  Suitable for starting and stopping
        |#  wrapped Java applications on UNIX platforms.
        |#
        |
        |#-----------------------------------------------------------------------------
        |# These settings can be modified to fit the needs of your application
        |
        |# Application
        |APP_NAME=%s
        |APP_LONG_NAME=%s
        |
        |if [ "X$APP_BASE" = "X" ]; then
        |  APP_BASE=..
        |fi
        |
        |# Wrapper
        |WRAPPER_CMD="./wrapper"
        |WRAPPER_CONF="$APP_BASE/conf/wrapper.conf"
        |
        |if [ ! -f "$WRAPPER_CONF" ]; then
        |  WRAPPER_CONF="../conf/wrapper.conf"
        |fi
        |
        |# Priority at which to run the wrapper.  See "man nice" for valid priorities.
        |#  nice is only used if a priority is specified.
        |PRIORITY=
        |
        |# Location of the pid file.
        |PIDDIR="$APP_BASE/logs"
        |
        |# If uncommented, causes the Wrapper to be shutdown using an anchor file.
        |#  When launched with the 'start' command, it will also ignore all INT and
        |#  TERM signals.
        |#IGNORE_SIGNALS=true
        |
        |# If specified, the Wrapper will be run as the specified user.
        |# IMPORTANT - Make sure that the user has the required privileges to write
        |#  the PID file and wrapper.log files.  Failure to be able to write the log
        |#  file will cause the Wrapper to exit without any way to write out an error
        |#  message.
        |# NOTE - This will set the user which is used to run the Wrapper as well as
        |#  the JVM and is not useful in situations where a privileged resource or
        |#  port needs to be allocated prior to the user being changed.
        |#RUN_AS_USER=
        |
        |# The following two lines are used by the chkconfig command. Change as is
        |#  appropriate for your application.  They should remain commented.
        |# chkconfig: 2345 20 80
        |# description: Iluka Distribution
        |
        |# Do not modify anything beyond this point
        |#-----------------------------------------------------------------------------
        |
        |# Get the fully qualified path to the script
        |case $0 in
        |    /*)
        |        SCRIPT="$0"
        |        ;;
        |    *)
        |        PWD=`pwd`
        |        SCRIPT="$PWD/$0"
        |        ;;
        |esac
        |
        |# Resolve the true real path without any sym links.
        |CHANGED=true
        |while [ "X$CHANGED" != "X" ]
        |do
        |    # Change spaces to ":" so the tokens can be parsed.
        |    SAFESCRIPT=`echo $SCRIPT | sed -e 's; ;:;g'`
        |    # Get the real path to this script, resolving any symbolic links
        |    TOKENS=`echo $SAFESCRIPT | sed -e 's;/; ;g'`
        |    REALPATH=
        |    for C in $TOKENS; do
        |        # Change any ":" in the token back to a space.
        |        C=`echo $C | sed -e 's;:; ;g'`
        |        REALPATH="$REALPATH/$C"
        |        # If REALPATH is a sym link, resolve it.  Loop for nested links.
        |        while [ -h "$REALPATH" ] ; do
        |            LS="`ls -ld "$REALPATH"`"
        |            LINK="`expr "$LS" : '.*-> \(.*\)$'`"
        |            if expr "$LINK" : '/.*' > /dev/null; then
        |                # LINK is absolute.
        |                REALPATH="$LINK"
        |            else
        |                # LINK is relative.
        |                REALPATH="`dirname "$REALPATH"`""/$LINK"
        |            fi
        |        done
        |    done
        |
        |    if [ "$REALPATH" = "$SCRIPT" ]
        |    then
        |        CHANGED=""
        |    else
        |        SCRIPT="$REALPATH"
        |    fi
        |done
        |
        |# Change the current directory to the location of the script
        |cd "`dirname "$REALPATH"`"
        |REALDIR=`pwd`
        |
        |# If the PIDDIR is relative, set its value relative to the full REALPATH to avoid problems if
        |#  the working directory is later changed.
        |FIRST_CHAR=`echo $PIDDIR | cut -c1,1`
        |if [ "$FIRST_CHAR" != "/" ]
        |then
        |    PIDDIR=$REALDIR/$PIDDIR
        |fi
        |# Same test for WRAPPER_CMD
        |FIRST_CHAR=`echo $WRAPPER_CMD | cut -c1,1`
        |if [ "$FIRST_CHAR" != "/" ]
        |then
        |    WRAPPER_CMD=$REALDIR/$WRAPPER_CMD
        |fi
        |# Same test for WRAPPER_CONF
        |FIRST_CHAR=`echo $WRAPPER_CONF | cut -c1,1`
        |if [ "$FIRST_CHAR" != "/" ]
        |then
        |    WRAPPER_CONF=$REALDIR/$WRAPPER_CONF
        |fi
        |
        |# Process ID
        |ANCHORFILE="$PIDDIR/$APP_NAME.anchor"
        |PIDFILE="$PIDDIR/$APP_NAME.pid"
        |LOCKDIR="/var/lock/subsys"
        |LOCKFILE="$LOCKDIR/$APP_NAME"
        |pid=""
        |
        |# Resolve the location of the 'ps' command
        |PSEXE="/usr/bin/ps"
        |if [ ! -x "$PSEXE" ]
        |then
        |    PSEXE="/bin/ps"
        |    if [ ! -x "$PSEXE" ]
        |    then
        |        echo "Unable to locate 'ps'."
        |        echo "Please report this message along with the location of the command on your system."
        |        exit 1
        |    fi
        |fi
        |
        |# Resolve the os
        |DIST_OS=`uname -s | tr [:upper:] [:lower:] | tr -d [:blank:]`
        |case "$DIST_OS" in
        |    'sunos')
        |        DIST_OS="solaris"
        |        ;;
        |    'hp-ux' | 'hp-ux64')
        |        DIST_OS="hpux"
        |        ;;
        |    'darwin')
        |        DIST_OS="macosx"
        |        ;;
        |    'unix_sv')
        |        DIST_OS="unixware"
        |        ;;
        |esac
        |
        |# Resolve the architecture
        |DIST_ARCH=`uname -p | tr [:upper:] [:lower:] | tr -d [:blank:]`
        |if [ "$DIST_ARCH" = "unknown" ]
        |then
        |    DIST_ARCH=`uname -m | tr [:upper:] [:lower:] | tr -d [:blank:]`
        |fi
        |case "$DIST_ARCH" in
        |    'amd64' | 'athlon' | 'ia32' | 'ia64' | 'i386' | 'i486' | 'i586' | 'i686' | 'x86_64')
        |        DIST_ARCH="x86"
        |        ;;
        |    'ip27')
        |        DIST_ARCH="mips"
        |        ;;
        |    'power' | 'powerpc' | 'power_pc' | 'ppc64')
        |        DIST_ARCH="ppc"
        |        ;;
        |    'pa_risc' | 'pa-risc')
        |        DIST_ARCH="parisc"
        |        ;;
        |    'sun4u' | 'sparcv9')
        |        DIST_ARCH="sparc"
        |        ;;
        |    '9000/800')
        |        DIST_ARCH="parisc"
        |        ;;
        |esac
        |
        |outputFile() {
        |    if [ -f "$1" ]
        |    then
        |        echo "  $1 (Found but not executable.)";
        |    else
        |        echo "  $1"
        |    fi
        |}
        |
        |# Decide on the wrapper binary to use.
        |# If a 32-bit wrapper binary exists then it will work on 32 or 64 bit
        |#  platforms, if the 64-bit binary exists then the distribution most
        |#  likely wants to use long names.  Otherwise, look for the default.
        |# For macosx, we also want to look for universal binaries.
        |WRAPPER_TEST_CMD="$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-32"
        |if [ -x "$WRAPPER_TEST_CMD" ]
        |then
        |    WRAPPER_CMD="$WRAPPER_TEST_CMD"
        |else
        |    if [ "$DIST_OS" = "macosx" ]
        |    then
        |        WRAPPER_TEST_CMD="$WRAPPER_CMD-$DIST_OS-universal-32"
        |        if [ -x "$WRAPPER_TEST_CMD" ]
        |        then
        |            WRAPPER_CMD="$WRAPPER_TEST_CMD"
        |        else
        |            WRAPPER_TEST_CMD="$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-64"
        |            if [ -x "$WRAPPER_TEST_CMD" ]
        |            then
        |                WRAPPER_CMD="$WRAPPER_TEST_CMD"
        |            else
        |                WRAPPER_TEST_CMD="$WRAPPER_CMD-$DIST_OS-universal-64"
        |                if [ -x "$WRAPPER_TEST_CMD" ]
        |                then
        |                    WRAPPER_CMD="$WRAPPER_TEST_CMD"
        |                else
        |                    if [ ! -x "$WRAPPER_CMD" ]
        |                    then
        |                        echo "Unable to locate any of the following binaries:"
        |                        outputFile "$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-32"
        |                        outputFile "$WRAPPER_CMD-$DIST_OS-universal-32"
        |                        outputFile "$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-64"
        |                        outputFile "$WRAPPER_CMD-$DIST_OS-universal-64"
        |                        outputFile "$WRAPPER_CMD"
        |                        exit 1
        |                    fi
        |                fi
        |            fi
        |        fi
        |    else
        |        WRAPPER_TEST_CMD="$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-64"
        |        if [ -x "$WRAPPER_TEST_CMD" ]
        |        then
        |            WRAPPER_CMD="$WRAPPER_TEST_CMD"
        |        else
        |            if [ ! -x "$WRAPPER_CMD" ]
        |            then
        |                echo "Unable to locate any of the following binaries:"
        |                outputFile "$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-32"
        |                outputFile "$WRAPPER_CMD-$DIST_OS-$DIST_ARCH-64"
        |                outputFile "$WRAPPER_CMD"
        |                exit 1
        |            fi
        |        fi
        |    fi
        |fi
        |
        |# Build the nice clause
        |if [ "X$PRIORITY" = "X" ]
        |then
        |    CMDNICE=""
        |else
        |    CMDNICE="nice -$PRIORITY"
        |fi
        |
        |# Build the anchor file clause.
        |if [ "X$IGNORE_SIGNALS" = "X" ]
        |then
        |   ANCHORPROP=
        |   IGNOREPROP=
        |else
        |   ANCHORPROP=wrapper.anchorfile=\"$ANCHORFILE\"
        |   IGNOREPROP=wrapper.ignore_signals=TRUE
        |fi
        |
        |# Build the lock file clause.  Only create a lock file if the lock directory exists on this platform.
        |LOCKPROP=
        |if [ -d $LOCKDIR ]
        |then
        |    if [ -w $LOCKDIR ]
        |    then
        |        LOCKPROP=wrapper.lockfile=\"$LOCKFILE\"
        |    fi
        |fi
        |
        |checkUser() {
        |    # $1 touchLock flag
        |    # $2 command
        |
        |    # Check the configured user.  If necessary rerun this script as the desired user.
        |    if [ "X$RUN_AS_USER" != "X" ]
        |    then
        |        # Resolve the location of the 'id' command
        |        IDEXE="/usr/xpg4/bin/id"
        |        if [ ! -x "$IDEXE" ]
        |        then
        |            IDEXE="/usr/bin/id"
        |            if [ ! -x "$IDEXE" ]
        |            then
        |                echo "Unable to locate 'id'."
        |                echo "Please report this message along with the location of the command on your system."
        |                exit 1
        |            fi
        |        fi
        |
        |        if [ "`$IDEXE -u -n`" = "$RUN_AS_USER" ]
        |        then
        |            # Already running as the configured user.  Avoid password prompts by not calling su.
        |            RUN_AS_USER=""
        |        fi
        |    fi
        |    if [ "X$RUN_AS_USER" != "X" ]
        |    then
        |        # If LOCKPROP and $RUN_AS_USER are defined then the new user will most likely not be
        |        # able to create the lock file.  The Wrapper will be able to update this file once it
        |        # is created but will not be able to delete it on shutdown.  If $2 is defined then
        |        # the lock file should be created for the current command
        |        if [ "X$LOCKPROP" != "X" ]
        |        then
        |            if [ "X$1" != "X" ]
        |            then
        |                # Resolve the primary group
        |                RUN_AS_GROUP=`groups $RUN_AS_USER | awk '{print $3}' | tail -1`
        |                if [ "X$RUN_AS_GROUP" = "X" ]
        |                then
        |                    RUN_AS_GROUP=$RUN_AS_USER
        |                fi
        |                touch $LOCKFILE
        |                chown $RUN_AS_USER:$RUN_AS_GROUP $LOCKFILE
        |            fi
        |        fi
        |
        |        # Still want to change users, recurse.  This means that the user will only be
        |        #  prompted for a password once. Variables shifted by 1
        |        su -m $RUN_AS_USER -c "\"$REALPATH\" $2"
        |
        |        # Now that we are the original user again, we may need to clean up the lock file.
        |        if [ "X$LOCKPROP" != "X" ]
        |        then
        |            getpid
        |            if [ "X$pid" = "X" ]
        |            then
        |                # Wrapper is not running so make sure the lock file is deleted.
        |                if [ -f "$LOCKFILE" ]
        |                then
        |                    rm "$LOCKFILE"
        |                fi
        |            fi
        |        fi
        |
        |        exit 0
        |    fi
        |}
        |
        |getpid() {
        |    if [ -f "$PIDFILE" ]
        |    then
        |        if [ -r "$PIDFILE" ]
        |        then
        |            pid=`cat "$PIDFILE"`
        |            if [ "X$pid" != "X" ]
        |            then
        |                # It is possible that 'a' process with the pid exists but that it is not the
        |                #  correct process.  This can happen in a number of cases, but the most
        |                #  common is during system startup after an unclean shutdown.
        |                # The ps statement below looks for the specific wrapper command running as
        |                #  the pid.  If it is not found then the pid file is considered to be stale.
        |                if [ "$DIST_OS" = "macosx" ]; then
        |                  pidtest=`$PSEXE -p $pid -o command -ww | grep "$WRAPPER_CMD" | tail -1`
        |                else
        |                  pidtest=`$PSEXE -p $pid -o args | grep "$WRAPPER_CMD" | tail -1`
        |                fi
        |                if [ "X$pidtest" = "X" ]
        |                then
        |                    # This is a stale pid file.
        |                    rm -f "$PIDFILE"
        |                    echo "Removed stale pid file: $PIDFILE"
        |                    pid=""
        |                fi
        |            fi
        |        else
        |            echo "Cannot read $PIDFILE."
        |            exit 1
        |        fi
        |    fi
        |}
        |
        |testpid() {
        |    pid=`$PSEXE -p $pid | grep $pid | grep -v grep | awk '{print $1}' | tail -1`
        |    if [ "X$pid" = "X" ]
        |    then
        |        # Process is gone so remove the pid file.
        |        rm -f "$PIDFILE"
        |        pid=""
        |    fi
        |}
        |
        |console() {
        |    echo "Running $APP_LONG_NAME..."
        |    getpid
        |    if [ "X$pid" = "X" ]
        |    then
        |        # The string passed to eval must handles spaces in paths correctly.
        |        COMMAND_LINE="$CMDNICE \"$WRAPPER_CMD\" \"$WRAPPER_CONF\" wrapper.syslog.ident=$APP_NAME wrapper.pidfile=\"$PIDFILE\" $ANCHORPROP $LOCKPROP"
        |        eval $COMMAND_LINE
        |    else
        |        echo "$APP_LONG_NAME is already running."
        |        exit 1
        |    fi
        |}
        |
        |start() {
        |    echo "Starting $APP_LONG_NAME..."
        |    getpid
        |    if [ "X$pid" = "X" ]
        |    then
        |        # The string passed to eval must handles spaces in paths correctly.
        |        COMMAND_LINE="$CMDNICE \"$WRAPPER_CMD\" \"$WRAPPER_CONF\" wrapper.syslog.ident=$APP_NAME wrapper.pidfile=\"$PIDFILE\" wrapper.daemonize=TRUE $ANCHORPROP $IGNOREPROP $LOCKPROP"
        |        eval $COMMAND_LINE
        |    else
        |        echo "$APP_LONG_NAME is already running."
        |        exit 1
        |    fi
        |}
        |
        |stopit() {
        |    echo "Stopping $APP_LONG_NAME..."
        |    getpid
        |    if [ "X$pid" = "X" ]
        |    then
        |        echo "$APP_LONG_NAME was not running."
        |    else
        |        if [ "X$IGNORE_SIGNALS" = "X" ]
        |        then
        |            # Running so try to stop it.
        |            kill $pid
        |            if [ $? -ne 0 ]
        |            then
        |                # An explanation for the failure should have been given
        |                echo "Unable to stop $APP_LONG_NAME."
        |                exit 1
        |            fi
        |        else
        |            rm -f "$ANCHORFILE"
        |            if [ -f "$ANCHORFILE" ]
        |            then
        |                # An explanation for the failure should have been given
        |                echo "Unable to stop $APP_LONG_NAME."
        |                exit 1
        |            fi
        |        fi
        |
        |        # We can not predict how long it will take for the wrapper to
        |        #  actually stop as it depends on settings in wrapper.conf.
        |        #  Loop until it does.
        |        savepid=$pid
        |        CNT=0
        |        TOTCNT=0
        |        while [ "X$pid" != "X" ]
        |        do
        |            # Show a waiting message every 5 seconds.
        |            if [ "$CNT" -lt "5" ]
        |            then
        |                CNT=`expr $CNT + 1`
        |            else
        |                echo "Waiting for $APP_LONG_NAME to exit..."
        |                CNT=0
        |            fi
        |            TOTCNT=`expr $TOTCNT + 1`
        |
        |            sleep 1
        |
        |            testpid
        |        done
        |
        |        pid=$savepid
        |        testpid
        |        if [ "X$pid" != "X" ]
        |        then
        |            echo "Failed to stop $APP_LONG_NAME."
        |            exit 1
        |        else
        |            echo "Stopped $APP_LONG_NAME."
        |        fi
        |    fi
        |}
        |
        |status() {
        |    getpid
        |    if [ "X$pid" = "X" ]
        |    then
        |        echo "$APP_LONG_NAME is not running."
        |        exit 1
        |    else
        |        echo "$APP_LONG_NAME is running ($pid)."
        |        exit 0
        |    fi
        |}
        |
        |dump() {
        |    echo "Dumping $APP_LONG_NAME..."
        |    getpid
        |    if [ "X$pid" = "X" ]
        |    then
        |        echo "$APP_LONG_NAME was not running."
        |
        |    else
        |        kill -3 $pid
        |
        |        if [ $? -ne 0 ]
        |        then
        |            echo "Failed to dump $APP_LONG_NAME."
        |            exit 1
        |        else
        |            echo "Dumped $APP_LONG_NAME."
        |        fi
        |    fi
        |}
        |
        |case "$1" in
        |
        |    'console')
        |        checkUser touchlock $1
        |        console
        |        ;;
        |
        |    'start')
        |        checkUser touchlock $1
        |        start
        |        ;;
        |
        |    'stop')
        |        checkUser "" $1
        |        stopit
        |        ;;
        |
        |    'restart')
        |        checkUser touchlock $1
        |        stopit
        |        start
        |        ;;
        |
        |    'status')
        |        checkUser "" $1
        |        status
        |        ;;
        |
        |    'dump')
        |        checkUser "" $1
        |        dump
        |        ;;
        |
        |    *)
        |        echo "Usage: $0 { console | start | stop | restart | status | dump }"
        |        exit 1
        |        ;;
        |esac
        |
        |exit 0
      """.stripMargin.format(appName, appName)


    def batScriptContent =
      """
        |@echo off
        |setlocal
        |
        |rem Copyright (c) 1999, 2009 Tanuki Software, Ltd.
        |rem http://www.tanukisoftware.com
        |rem All rights reserved.
        |rem
        |rem This software is the proprietary information of Tanuki Software.
        |rem You shall use it only in accordance with the terms of the
        |rem license agreement you entered into with Tanuki Software.
        |rem http://wrapper.tanukisoftware.org/doc/english/licenseOverview.html
        |rem
        |rem Java Service Wrapper general startup script.
        |rem Optimized for use with version 3.3.6 of the Wrapper.
        |rem
        |
        |rem
        |rem Resolve the real path of the wrapper.exe
        |rem  For non NT systems, the _REALPATH and _WRAPPER_CONF values
        |rem  can be hard-coded below and the following test removed.
        |rem
        |if "%OS%"=="Windows_NT" goto nt
        |echo This script only works with NT-based versions of Windows.
        |goto :eof
        |
        |:nt
        |rem
        |rem Find the application home.
        |rem
        |rem %~dp0 is location of current script under NT
        |set _REALPATH=%~dp0
        |
        |rem Decide on the wrapper binary.
        |set _WRAPPER_BASE=wrapper
        |set _WRAPPER_EXE=%_REALPATH%%_WRAPPER_BASE%-windows-x86-32.exe
        |if exist "%_WRAPPER_EXE%" goto conf
        |set _WRAPPER_EXE=%_REALPATH%%_WRAPPER_BASE%-windows-x86-64.exe
        |if exist "%_WRAPPER_EXE%" goto conf
        |set _WRAPPER_EXE=%_REALPATH%%_WRAPPER_BASE%.exe
        |if exist "%_WRAPPER_EXE%" goto conf
        |echo Unable to locate a Wrapper executable using any of the following names:
        |echo %_REALPATH%%_WRAPPER_BASE%-windows-x86-32.exe
        |echo %_REALPATH%%_WRAPPER_BASE%-windows-x86-64.exe
        |echo %_REALPATH%%_WRAPPER_BASE%.exe
        |pause
        |goto :eof
        |
        |rem
        |rem Find the wrapper.conf
        |rem
        |:conf
        |set _WRAPPER_CONF="%~f1"
        |if not %_WRAPPER_CONF%=="" goto startup
        |set _WRAPPER_CONF="%_REALPATH%..\conf\wrapper.conf"
        |
        |rem
        |rem Start the Wrapper
        |rem
        |:startup
        |"%_WRAPPER_EXE%" -c %_WRAPPER_CONF%
        |if not errorlevel 1 goto :eof
        |pause
      """.stripMargin

  }

  private case class JswConf(appName: String, mainClass: String, libPaths: String, initHeapInMB: Int, maxHeapInMB: Int) {

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
        |wrapper.working.dir=..
        |
        |# Tell the Wrapper to log the full generated Java command line.
        |#wrapper.java.command.loglevel=INFO
        |
        |# Java Main class.  This class must implement the WrapperListener interface
        |#  or guarantee that the WrapperManager class is initialized.  Helper
        |#  classes are provided to do this for you.  See the Integration section
        |#  of the documentation for details.
        |wrapper.java.mainclass=org.tanukisoftware.wrapper.WrapperSimpleApp
        |set.default.REPO_DIR=lib
        |set.default.APP_BASE=.
        |
        |# Java Classpath (include wrapper.jar)  Add class path elements as
        |#  needed starting from 1
        |wrapper.java.classpath.1=lib/wrapper.jar
        |%s
        |# Java Library Path (location of Wrapper.DLL or libwrapper.so)
        |wrapper.java.library.path.1=lib
        |
        |# Java Bits.  On applicable platforms, tells the JVM to run in 32 or 64-bit mode.
        |wrapper.java.additional.auto_bits=TRUE
        |
        |# Java Additional Parameters
        |#wrapper.java.additional.1=
        |
        |# Initial Java Heap Size (in MB)
        |wrapper.java.initmemory=%s
        |
        |# Maximum Java Heap Size (in MB)
        |wrapper.java.maxmemory=%s
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
        |wrapper.logfile=logs/wrapper.log
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
        |
        |configuration.directory.in.classpath.first=conf
      """.stripMargin.format(libPaths, initHeapInMB, maxHeapInMB, mainClass, appName, appName, appName, appName)

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

    def include(project: ResolvedProject): Boolean = {
      projDepsNames.exists(_ == project.id)
    }

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
        state.log.error(errorMessage)
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

