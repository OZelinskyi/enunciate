package net.sf.enunciate.main;

import net.sf.enunciate.apt.EnunciateAnnotationProcessorFactory;
import net.sf.enunciate.modules.DeploymentModule;
import net.sf.enunciate.modules.xfire.XFireDeploymentModule;
import net.sf.enunciate.modules.xml.XMLDeploymentModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main enunciate entry point.
 *
 * @author Ryan Heaton
 */
public class Enunciate {

  /**
   * The targets for the project.
   */
  public enum Target {

    /**
     * Generate the XML APIs and source code.
     */
    GENERATE,

    /**
     * Generate and compile.
     */
    COMPILE,

    /**
     * Compile and build the war structure.
     */
    BUILD,

    /**
     * Build and package the war.
     */
    PACKAGE
  }

  private boolean verbose = false;
  private boolean debug = false;

  private File config;
  private File preprocessDir;
  private File destDir;
  private File warBuildDir;
  private File warFile;
  private String classpath;
  private String warLibs;
  private List<DeploymentModule> deploymentModules;
  private Target target = Target.PACKAGE;

  public static void main(String[] args) {
    Enunciate enunciate = new Enunciate();
    //todo: compile the args, set the variables, then:
    //enunciate.execute();
  }

  /**
   * Constructs an enunciate mechanism with the default modules.
   */
  public Enunciate() {
    this.deploymentModules = new ArrayList<DeploymentModule>();
    this.deploymentModules.add(new XMLDeploymentModule());
    this.deploymentModules.add(new XFireDeploymentModule());
  }

  /**
   * Enunciate the specified source files.
   *
   * @param sourceFiles The source files to enunciate.
   */
  public void execute(String[] sourceFiles) throws IOException {
    for (DeploymentModule deploymentModule : getDeploymentModules()) {
      deploymentModule.init(this);
    }

    boolean success = invokeApt(sourceFiles);

    if (success && (getTarget().ordinal() >= Target.COMPILE.ordinal())) {
      File destdir = getDestDir();
      if (destdir == null) {
        destdir = File.createTempFile("enunciate", "");
        destdir.delete();
        destdir.mkdirs();
        setDestDir(destdir);
      }

      success = invokeJavac(sourceFiles);

      for (DeploymentModule deploymentModule : getDeploymentModules()) {
        deploymentModule.step(Target.COMPILE);
      }
    }

    if (success && (getTarget().ordinal() >= Target.BUILD.ordinal())) {
      File warbuilddir = getWarBuildDir();
      if (warbuilddir == null) {
        warbuilddir = File.createTempFile("enunciate", "");
        warbuilddir.delete();
        warbuilddir.mkdirs();
        setWarBuildDir(warbuilddir);
      }

      File webinfClasses = new File(getWebInf(), "classes");
      File webinfLib = new File(getWebInf(), "lib");

      //copy the compiled classes to WEB-INF/classes.
      copyDir(getDestDir(), webinfClasses);

      String warLibs = getWarLibs();
      if (warLibs == null) {
        warLibs = getClasspath();
      }

      if (warLibs != null) {
        String[] pathEntries = warLibs.split(File.pathSeparator);
        for (String pathEntry : pathEntries) {
          File file = new File(pathEntry);
          if (file.exists()) {
            if (file.isDirectory()) {
              if (isVerbose()) {
                System.out.println("Adding the contents of " + file.getAbsolutePath() + " to WEB-INF/classes.");
              }
              copyDir(file, webinfClasses);
            }
            else {
              if (isVerbose()) {
                System.out.println("Including " + file.getName() + " in WEB-INF/lib.");
              }
              copyFile(file, file.getParentFile(), webinfLib);
            }
          }
        }
      }

      //todo: copy any additional files as resources (specified in the config);

      //todo: copy any additional jars (specified in the config)?

      //todo: assert that the necessary jars (spring, xfire, commons-whatever, etc.) are there?

      for (DeploymentModule deploymentModule : getDeploymentModules()) {
        deploymentModule.step(Target.BUILD);
      }
    }

    if (success && (getTarget().ordinal() >= Target.PACKAGE.ordinal())) {
      File warBuildDir = getWarBuildDir();
      if (warBuildDir == null) {
        throw new IOException("No war build directory has been specified.");
      }

      if (!warBuildDir.exists()) {
        throw new IOException("Directory doesn't exist: " + warBuildDir.getAbsolutePath());
      }

      if (!warBuildDir.isDirectory()) {
        throw new IOException(warBuildDir.getAbsolutePath() + " is not a directory.");
      }

      if (warBuildDir.list().length == 0) {
        throw new IOException(warBuildDir.getAbsolutePath() + " is an empty directory.");
      }

      File warFile = getWarFile();
      if (warFile == null) {
        throw new IOException("A war file must be specified.");
      }

      if (!warFile.getParentFile().exists()) {
        warFile.getParentFile().mkdirs();
      }

      if (isVerbose()) {
        System.out.println("Creating " + warFile.getAbsolutePath());
      }

      zip(warBuildDir, warFile);

      for (DeploymentModule deploymentModule : getDeploymentModules()) {
        deploymentModule.step(Target.PACKAGE);
      }
    }

    for (DeploymentModule deploymentModule : getDeploymentModules()) {
      deploymentModule.close();
    }
  }

  public File getWebInf() {
    return new File(getWarBuildDir(), "WEB-INF");
  }

  /**
   * Invokes APT on the specified source files.
   *
   * @param sourceFiles The source files.
   * @return Whether the invocation was successful.
   */
  protected boolean invokeApt(String[] sourceFiles) throws IOException {
    ArrayList<String> args = new ArrayList<String>();
    String classpath = getClasspath();
    if (classpath == null) {
      classpath = System.getProperty("java.class.path");
    }

    args.add("-cp");
    args.add(classpath);

    if (isVerbose()) {
      args.add(EnunciateAnnotationProcessorFactory.VERBOSE_OPTION);
    }

    args.add("-nocompile");

    if (getPreprocessDir() != null) {
      args.add("-s");
      args.add(getPreprocessDir().getAbsolutePath());
    }

    args.addAll(Arrays.asList(sourceFiles));

    if (isDebug()) {
      System.out.println("Invoking APT with arguments: ");
      for (String arg : args) {
        System.out.println(arg);
      }
    }

    EnunciateAnnotationProcessorFactory apf = new EnunciateAnnotationProcessorFactory(getDeploymentModules());
    int procCode = com.sun.tools.apt.Main.process(apf, args.toArray(new String[args.size()]));
    return apf.isProcessedSuccessfully() && (procCode == 0);
  }

  /**
   * Invokes javac on the specified source files.
   *
   * @param sourceFiles The source files.
   * @return Whether the invocation was successful.
   */
  protected boolean invokeJavac(String[] sourceFiles) throws IOException {
    ArrayList<String> args = new ArrayList<String>();
    String classpath = getClasspath();
    if (classpath == null) {
      classpath = System.getProperty("java.class.path");
    }

    args.add("-cp");
    args.add(classpath);

    if (isDebug()) {
      args.add("-verbose");
    }

    args.add("-d");
    args.add(getDestDir().getAbsolutePath());

    args.addAll(Arrays.asList(sourceFiles));

    if (isDebug()) {
      System.out.println("Invoking Javac with arguments: ");
      for (String arg : args) {
        System.out.println(arg);
      }
    }

    int procCode = com.sun.tools.javac.Main.compile(args.toArray(new String[args.size()]));
    return (procCode == 0);
  }

  /**
   * Copy an entire directory from one place to another.
   *
   * @param from The source directory.
   * @param to   The destination directory.
   */
  public void copyDir(File from, File to) throws IOException {
    if (!to.exists()) {
      to.mkdirs();
    }

    File[] files = from.listFiles();
    for (File file : files) {
      if (file.isDirectory()) {
        copyDir(file, new File(to, file.getName()));
      }
      else {
        copyFile(file, new File(to, file.getName()));
      }
    }
  }

  /**
   * Copy a file from one directory to another, preserving directory structure.
   *
   * @param src     The source file.
   * @param fromDir The from directory.
   * @param toDir   The to directory.
   */
  public void copyFile(File src, File fromDir, File toDir) throws IOException {
    URI fromURI = fromDir.toURI();
    URI srcURI = src.toURI();
    URI relativeURI = fromURI.relativize(srcURI);
    File toFile = new File(toDir, relativeURI.getPath());
    copyFile(src, toFile);
  }

  /**
   * Copy a file from one location to another.
   *
   * @param from The source file.
   * @param to   The destination file.
   */
  public void copyFile(File from, File to) throws IOException {
    FileChannel srcChannel = new FileInputStream(from).getChannel();
    if (!to.exists()) {
      to.getParentFile().mkdirs();
    }

    FileChannel dstChannel = new FileOutputStream(to, false).getChannel();
    dstChannel.transferFrom(srcChannel, 0, srcChannel.size());
    srcChannel.close();
    dstChannel.close();
  }

  /**
   * Copies a resource on the classpath to a file.
   *
   * @param resource The resource to copy.
   * @param to       The file to copy to.
   */
  public void copyResource(String resource, File to) throws IOException {
    URL resourceURL = getClass().getResource(resource);
    try {
      copyFile(new File(resourceURL.toURI()), to);
    }
    catch (URISyntaxException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * zip up a directory to a specified zip file.
   *
   * @param dir    The directory to zip up.
   * @param toFile The file to zip to.
   */
  public void zip(File dir, File toFile) throws IOException {
    ArrayList<File> files = new ArrayList<File>();
    buildFileList(dir, files);

    byte[] buffer = new byte[2 * 1024]; //buffer of 2K should be fine.
    URI baseURI = dir.toURI();
    ZipOutputStream zipout = new ZipOutputStream(new FileOutputStream(toFile));
    for (File file : files) {
      ZipEntry entry = new ZipEntry(baseURI.relativize(file.toURI()).getPath());

      if (isDebug()) {
        System.out.println("Adding entry " + entry.getName());
      }

      zipout.putNextEntry(entry);

      FileInputStream in = new FileInputStream(file);
      int len;
      while ((len = in.read(buffer)) > 0) {
        zipout.write(buffer, 0, len);
      }

      // Complete the entry
      zipout.closeEntry();
      in.close();
    }

    zipout.close();
  }

  /**
   * Adds all files in a specified directory to a list.
   *
   * @param dir  The directory.
   * @param list The list.
   */
  protected void buildFileList(File dir, List<File> list) {
    for (File file : dir.listFiles()) {
      if (file.isDirectory()) {
        buildFileList(file, list);
      }
      else {
        list.add(file);
      }
    }
  }

  /**
   * Whether to be verbose.
   *
   * @return Whether to be verbose.
   */
  public boolean isVerbose() {
    return verbose;
  }

  /**
   * Whether to be verbose.
   *
   * @param verbose Whether to be verbose.
   */
  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  /**
   * Whether to print debugging information.
   *
   * @return Whether to print debugging information.
   */
  public boolean isDebug() {
    return debug;
  }

  /**
   * Whether to print debugging information.
   *
   * @param debug Whether to print debugging information.
   */
  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  /**
   * The enunciate config file.
   *
   * @return The enunciate config file.
   */
  public File getConfig() {
    return config;
  }

  /**
   * The enunciate config file.
   *
   * @param config The enunciate config file.
   */
  public void setConfig(File config) {
    this.config = config;
  }

  /**
   * The destination directory for the compiled classes.
   *
   * @return The destination directory for the compiled classes.
   */
  public File getDestDir() {
    return destDir;
  }

  /**
   * The destination directory for the compiled classes.
   *
   * @param destDir The destination directory for the compiled classes.
   */
  public void setDestDir(File destDir) {
    this.destDir = destDir;
  }

  /**
   * The directory to use to build the war.
   *
   * @return The directory to use to build the war.
   */
  public File getWarBuildDir() {
    return warBuildDir;
  }

  /**
   * The directory to use to build the war.
   *
   * @param warBuildDir The directory to use to build the war.
   */
  public void setWarBuildDir(File warBuildDir) {
    this.warBuildDir = warBuildDir;
  }

  /**
   * The war file to create.
   *
   * @return The war file to create.
   */
  public File getWarFile() {
    return warFile;
  }

  /**
   * The war file to create.
   *
   * @param warFile The war file to create.
   */
  public void setWarFile(File warFile) {
    this.warFile = warFile;
  }

  /**
   * The preprocessor directory (-s).
   *
   * @return The preprocessor directory (-s).
   */
  public File getPreprocessDir() {
    return preprocessDir;
  }

  /**
   * The preprocessor directory (-s).
   *
   * @param preprocessDir The preprocessor directory (-s).
   */
  public void setPreprocessDir(File preprocessDir) {
    this.preprocessDir = preprocessDir;
  }

  /**
   * The classpath.
   *
   * @return The classpath.
   */
  public String getClasspath() {
    return classpath;
  }

  /**
   * The classpath.
   *
   * @param classpath The classpath.
   */
  public void setClasspath(String classpath) {
    this.classpath = classpath;
  }

  /**
   * The libraries to include in the war, in classpath form.  Defaults to the classpath.
   *
   * @return The libraries to include in the war, in classpath form.  Defaults to the classpath.
   */
  public String getWarLibs() {
    return warLibs;
  }

  /**
   * The libraries to include in the war, in classpath form.  Defaults to the classpath.
   *
   * @param warLibs The libraries to include in the war, in classpath form.  Defaults to the classpath.
   */
  public void setWarLibs(String warLibs) {
    this.warLibs = warLibs;
  }

  /**
   * The target.
   *
   * @return The target.
   */
  public Target getTarget() {
    return target;
  }

  /**
   * The target.
   *
   * @param target The target.
   */
  public void setTarget(Target target) {
    this.target = target;
  }

  /**
   * The list of deployment modules to use to enunciate.
   *
   * @return The list of deployment modules to use to enunciate.
   */
  public List<DeploymentModule> getDeploymentModules() {
    return deploymentModules;
  }

  /**
   * The list of deployment modules to use to enunciate.
   *
   * @param deploymentModules The list of deployment modules to use to enunciate.
   */
  public void setDeploymentModules(List<DeploymentModule> deploymentModules) {
    this.deploymentModules = deploymentModules;
  }
}