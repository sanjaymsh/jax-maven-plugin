package com.github.davidmoten.jaxws;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

import com.google.common.collect.Lists;

abstract class BaseMojo extends AbstractMojo {
    
    @Parameter(required = true, name = "arguments")
    private List<String> arguments;

    @Parameter(name = "systemProperties")
    private Map<String, String> systemProperties;

    @Component
    private MavenProject project;

    @Component
    private BuildContext buildContext;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;

    private final JaxCommand cmd;
    
    BaseMojo(JaxCommand cmd) {
        this.cmd = cmd;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();
        log.info("Starting " + cmd + " mojo");

        List<File> outputDirectories = cmd. //
                getDirectoryParameters() //
                .stream() //
                .map(param -> Util.createOutputDirectoryIfSpecifiedOrDefault(log, param, arguments))
                .collect(Collectors.toList());
        try {
            List<String> command = createCommand();

            new ProcessExecutor() //
                    .command(command) //
                    .exitValueNormal() //
                    .redirectOutput(System.out) //
                    .redirectError(System.out) //
                    .execute();

            outputDirectories.forEach(buildContext::refresh);

        } catch (InvalidExitValueException | IOException | InterruptedException | TimeoutException
                | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        log.info(cmd + " mojo finished");
    }

    private List<String> createCommand()
            throws DependencyResolutionRequiredException {

        Log log = getLog();
        
        // https://stackoverflow.com/questions/1440224/how-can-i-download-maven-artifacts-within-a-plugin

        String mainArtifactVersion = Util.readConfigurationValue("project.parent.version");
        String mainArtifactGroupId = Util.readConfigurationValue("project.parent.groupId");

        ///////////////////////////////////////////////////////////////////
        //
        // get the classpath entries for the deps of the main class called
        //
        //////////////////////////////////////////////////////////////////

        Artifact artifact = repositorySystem.createArtifact( //
                mainArtifactGroupId, "jaxws-maven-plugin-core", mainArtifactVersion, "", "jar");

        log.info("setting up classpath for jaxws-tools version " + mainArtifactVersion);

        ArtifactResolutionResult r = Util.resolve(log, artifact, repositorySystem, localRepository, remoteRepositories);

        Stream<String> artifactEntry = Stream.of(artifact.getFile().getAbsolutePath());

        Stream<String> dependencyEntries = r.getArtifactResolutionNodes() //
                .stream() //
                .map(x -> x.getArtifact().getFile().getAbsolutePath());

        Stream<String> pluginDependencyEntries = Util.getPluginRuntimeDependencyEntries(this, project, log,
                repositorySystem, localRepository, remoteRepositories);

        Stream<String> fullDependencyEntries = Stream.concat(//
                dependencyEntries, //
                pluginDependencyEntries);

        StringBuilder classpath = new StringBuilder();
        classpath.append( //
                Stream.concat(artifactEntry, fullDependencyEntries) //
                        .collect(Collectors.joining(File.pathSeparator)));

        ////////////////////////////////////////////////////////
        //
        // now grab the classpath entry for *-maven-plugin-core
        //
        ////////////////////////////////////////////////////////
        final URLClassLoader classLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader();

        for (final URL url : classLoader.getURLs()) {
            File file = new File(url.getFile());
            log.debug("plugin classpath entry: " + file.getAbsolutePath());
            // Note the contains check on xjc-maven-plugin-core because Travis runs mvn test
            // -B which gives us a classpath entry of xjc-maven-plugin-core/target/classes
            // (not a jar)
            if (file.getAbsolutePath().contains("xjc-maven-plugin-core")
                    || file.getAbsolutePath().contains("jaxws-maven-plugin-core")) {
                if (classpath.length() > 0) {
                    classpath.append(File.pathSeparator);
                }
                classpath.append(file.getAbsolutePath());
            }
        }
        log.debug("isolated classpath for call to " + cmd + "=\n  "
                + classpath.toString().replace(File.pathSeparator, File.pathSeparator + "\n  "));

        final String javaExecutable = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + "java";
        List<String> command = Lists.newArrayList( //
                javaExecutable, //
                "-classpath", //
                classpath.toString());
        if (systemProperties != null) {
            command.addAll(systemProperties //
                    .entrySet() //
                    .stream() //
                    .map(entry -> "-D" + entry.getKey() + "=" + entry.getValue()) //
                    .collect(Collectors.toList()));
        }
        command.add(cmd.mainClass().getName());

        // if -cp or -classpath parameter not set in arguments then use classpathScope
        if (this instanceof HasClasspathScope) {
            String classpathScope = ((HasClasspathScope) this).classpathScope();
            if (!arguments //
                    .stream() //
                    .filter(x -> "-cp".equals(x.trim()) || "-classpath".equals(x.trim())) //
                    .findFirst() //
                    .isPresent()) {
                List<String> cp;
                if ("compile".equals(classpathScope)) {
                    cp = project.getCompileClasspathElements();
                } else if ("runtime".equals(classpathScope)) {
                    cp = project.getRuntimeClasspathElements();
                } else if ("test".equals(classpathScope)) {
                    cp = project.getTestClasspathElements();
                } else {
                    throw new IllegalArgumentException("classpathScope " + classpathScope + " not recognized");
                }
                command.add(cp.stream().collect(Collectors.joining(File.pathSeparator)));
            }
        }
        command.addAll(arguments);
        log.info("arguments passed to " + cmd.mainClass().getName() + ":\n  "
                + command.stream().collect(Collectors.joining("\n  ")) + "\n");
        return command;
    }
}
