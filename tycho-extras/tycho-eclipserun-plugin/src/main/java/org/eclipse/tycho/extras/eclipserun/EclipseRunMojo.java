/*******************************************************************************
 * Copyright (c) 2011, 2016 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *    Marc-Andre Laperle - EclipseRunMojo inspired by TestMojo
 *******************************************************************************/
package org.eclipse.tycho.extras.eclipserun;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.sisu.equinox.launching.DefaultEquinoxInstallationDescription;
import org.eclipse.sisu.equinox.launching.EquinoxInstallation;
import org.eclipse.sisu.equinox.launching.EquinoxInstallationDescription;
import org.eclipse.sisu.equinox.launching.EquinoxInstallationFactory;
import org.eclipse.sisu.equinox.launching.EquinoxLauncher;
import org.eclipse.sisu.equinox.launching.internal.EquinoxLaunchConfiguration;
import org.eclipse.tycho.ArtifactType;
import org.eclipse.tycho.DefaultArtifactKey;
import org.eclipse.tycho.artifacts.IllegalArtifactReferenceException;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfigurationStub;
import org.eclipse.tycho.core.maven.ToolchainProvider;
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation;
import org.eclipse.tycho.launching.LaunchConfiguration;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult.Entry;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.eclipse.tycho.plugins.p2.extras.Repository;

/**
 * Launch an eclipse process with arbitrary commandline arguments. The eclipse installation is
 * defined by the dependencies to bundles specified.
 */
@Mojo(name = "eclipse-run")
public class EclipseRunMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.directory}/eclipserun-work")
    private File work;

    @Parameter(property = "project")
    private MavenProject project;

    /**
     * Dependencies which will be resolved transitively to make up the eclipse runtime. Example:
     * 
     * <pre>
     * &lt;dependencies&gt;
     *  &lt;dependency&gt;
     *   &lt;artifactId&gt;org.eclipse.ant.core&lt;/artifactId&gt;
     *   &lt;type&gt;eclipse-plugin&lt;/type&gt;
     *  &lt;/dependency&gt;
     * &lt;/dependencies&gt;
     * </pre>
     */
    @Parameter
    private List<Dependency> dependencies = new ArrayList<>();

    /**
     * Whether to add default dependencies to bundles org.eclipse.equinox.launcher, org.eclipse.osgi
     * and org.eclipse.core.runtime.
     */
    @Parameter(defaultValue = "true")
    private boolean addDefaultDependencies;

    /**
     * Execution environment profile name used to resolve dependencies.
     */
    @Parameter(defaultValue = "JavaSE-1.7")
    private String executionEnvironment;

    /**
     * p2 repositories which will be used to resolve dependencies. Example:
     * 
     * <pre>
     * &lt;repositories&gt;
     *  &lt;repository&gt;
     *   &lt;id&gt;juno&lt;/id&gt;
     *   &lt;layout&gt;p2&lt;/layout&gt;
     *   &lt;url&gt;https://download.eclipse.org/releases/juno&lt;/url&gt;
     *  &lt;/repository&gt;
     * &lt;/repositories&gt;
     * </pre>
     */
    @Parameter(required = true)
    private List<Repository> repositories;

    @Parameter(property = "session", readonly = true, required = true)
    private MavenSession session;

    /**
     * Arbitrary JVM options to set on the command line.
     * 
     * @deprecated use {@link #jvmArgs} instead.
     */
    @Parameter
    private String argLine;

    /**
     * List of JVM arguments set on the command line. Example:
     * 
     * <pre>
     * &lt;jvmArgs&gt;
     *   &lt;args&gt;-Xdebug&lt;/args&gt;
     *   &lt;args&gt;-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=1044&lt;/args&gt;
     * &lt;/jvmArgs&gt;
     * </pre>
     * 
     * @since 0.25.0
     */
    @Parameter
    private List<String> jvmArgs;

    /**
     * Whether to skip mojo execution.
     */
    @Parameter(property = "eclipserun.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Arbitrary applications arguments to set on the command line.
     * 
     * @deprecated use {@link #applicationsArgs} instead.
     */
    @Parameter
    private String appArgLine;

    /**
     * List of applications arguments set on the command line. Example:
     * 
     * <pre>
     * &lt;applicationsArgs&gt;
     *   &lt;args&gt;-buildfile&lt;/args&gt;
     *   &lt;args&gt;build-test.xml&lt;/args&gt;
     * &lt;/applicationsArgs&gt;
     * </pre>
     * 
     * @since 0.24.0
     */
    @Parameter
    private List<String> applicationsArgs;

    /**
     * Kill the forked process after a certain number of seconds. If set to 0, wait forever for the
     * process, never timing out.
     */
    @Parameter(property = "eclipserun.timeout")
    private int forkedProcessTimeoutInSeconds;

    /**
     * Additional environments to set for the forked JVM.
     */
    @Parameter
    private Map<String, String> environmentVariables;

    @Component
    private EquinoxInstallationFactory installationFactory;

    @Component
    private EquinoxLauncher launcher;

    @Component
    private ToolchainProvider toolchainProvider;

    @Component
    private EquinoxServiceFactory equinox;

    @Component
    private Logger logger;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().debug("skipping mojo execution");
            return;
        }
        EquinoxInstallation installation = createEclipseInstallation();
        runEclipse(installation);
    }

    private void addDefaultDependency(P2Resolver resolver, String bundleId) {
        try {
            resolver.addDependency(ArtifactType.TYPE_ECLIPSE_PLUGIN, bundleId, null);
        } catch (IllegalArtifactReferenceException e) {
            // shouldn't happen for the constant type and version
            throw new RuntimeException(e);
        }
    }

    private void addDefaultDependencies(P2Resolver resolver) {
        if (addDefaultDependencies) {
            addDefaultDependency(resolver, "org.eclipse.osgi");
            addDefaultDependency(resolver, EquinoxInstallationDescription.EQUINOX_LAUNCHER);
            addDefaultDependency(resolver, "org.eclipse.core.runtime");
        }
    }

    private EquinoxInstallation createEclipseInstallation() throws MojoFailureException {
        P2ResolverFactory resolverFactory = equinox.getService(P2ResolverFactory.class);
        TargetPlatformConfigurationStub tpConfiguration = new TargetPlatformConfigurationStub();
        // we want to resolve from remote repos only
        tpConfiguration.setForceIgnoreLocalArtifacts(true);
        for (Repository repository : repositories) {
            tpConfiguration.addP2Repository(new MavenRepositoryLocation(repository.getId(), repository.getLocation()));
        }
        TargetPlatform targetPlatform = resolverFactory.getTargetPlatformFactory().createTargetPlatform(tpConfiguration,
                new ExecutionEnvironmentConfigurationStub(executionEnvironment), null, null);
        P2Resolver resolver = resolverFactory.createResolver(new MavenLoggerAdapter(logger, false));
        for (Dependency dependency : dependencies) {
            try {
                resolver.addDependency(dependency.getType(), dependency.getArtifactId(), dependency.getVersion());
            } catch (IllegalArtifactReferenceException e) {
                throw new MojoFailureException("Invalid dependency " + dependency.getType() + ":"
                        + dependency.getArtifactId() + ":" + dependency.getVersion() + ": " + e.getMessage(), e);
            }
        }
        addDefaultDependencies(resolver);
        EquinoxInstallationDescription installationDesc = new DefaultEquinoxInstallationDescription();
        for (P2ResolutionResult result : resolver.resolveDependencies(targetPlatform, null)) {
            for (Entry entry : result.getArtifacts()) {
                if (ArtifactType.TYPE_ECLIPSE_PLUGIN.equals(entry.getType())) {
                    installationDesc.addBundle(
                            new DefaultArtifactKey(ArtifactType.TYPE_ECLIPSE_PLUGIN, entry.getId(), entry.getVersion()),
                            entry.getLocation());
                }
            }
        }
        return installationFactory.createInstallation(installationDesc, work);
    }

    private void runEclipse(EquinoxInstallation runtime) throws MojoExecutionException, MojoFailureException {
        try {
            File workspace = new File(work, "data").getAbsoluteFile();
            FileUtils.deleteDirectory(workspace);
            LaunchConfiguration cli = createCommandLine(runtime);
            getLog().info("Expected eclipse log file: " + new File(workspace, ".metadata/.log").getCanonicalPath());
            int returnCode = launcher.execute(cli, forkedProcessTimeoutInSeconds);
            if (returnCode != 0) {
                throw new MojoExecutionException("Error while executing platform (return code: " + returnCode + ")");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error while executing platform", e);
        }
    }

    LaunchConfiguration createCommandLine(EquinoxInstallation runtime)
            throws MalformedURLException, MojoExecutionException {
        EquinoxLaunchConfiguration cli = new EquinoxLaunchConfiguration(runtime);

        String executable = null;
        Toolchain tc = getToolchain();
        if (tc != null) {
            getLog().info("Toolchain in tycho-eclipserun-plugin: " + tc);
            executable = tc.findTool("java");
        }
        cli.setJvmExecutable(executable);
        cli.setWorkingDirectory(project.getBasedir());

        cli.addVMArguments(splitArgLine(argLine));
        if (jvmArgs != null) {
            cli.addVMArguments(jvmArgs.toArray(new String[jvmArgs.size()]));
        }

        addProgramArgs(cli, "-install", runtime.getLocation().getAbsolutePath(), "-configuration",
                new File(work, "configuration").getAbsolutePath());

        cli.addProgramArguments(splitArgLine(appArgLine));
        if (applicationsArgs != null) {
            for (String args : applicationsArgs) {
                cli.addProgramArguments(splitArgLine(args));
            }
        }

        if (environmentVariables != null) {
            cli.addEnvironmentVariables(environmentVariables);
        }

        return cli;
    }

    private String[] splitArgLine(String argumentLine) throws MojoExecutionException {
        try {
            return CommandLineUtils.translateCommandline(argumentLine);
        } catch (Exception e) {
            throw new MojoExecutionException("Error parsing commandline: " + e.getMessage(), e);
        }
    }

    private void addProgramArgs(EquinoxLaunchConfiguration cli, String... arguments) {
        if (arguments != null) {
            for (String argument : arguments) {
                if (argument != null) {
                    cli.addProgramArguments(argument);
                }
            }
        }
    }

    private Toolchain getToolchain() throws MojoExecutionException {
        return toolchainProvider.findMatchingJavaToolChain(session, executionEnvironment);
    }

}
