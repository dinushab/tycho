/*******************************************************************************
 * Copyright (c) 2008, 2014 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.core.resolver;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.tycho.artifacts.configuration.TargetPlatformFilterConfigurationReader;
import org.eclipse.tycho.core.TargetPlatformConfiguration;
import org.eclipse.tycho.core.TargetPlatformConfiguration.BREEHeaderSelectionPolicy;
import org.eclipse.tycho.core.TychoConstants;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.resolver.shared.IncludeSourceMode;
import org.eclipse.tycho.core.resolver.shared.OptionalResolutionAction;
import org.eclipse.tycho.core.resolver.shared.PlatformPropertiesUtils;
import org.eclipse.tycho.core.shared.BuildFailureException;
import org.eclipse.tycho.core.shared.TargetEnvironment;

@Component(role = DefaultTargetPlatformConfigurationReader.class)
public class DefaultTargetPlatformConfigurationReader {
    private static final String OPTIONAL_RESOLUTION_REQUIRE = "require";
    private static final String OPTIONAL_RESOLUTION_IGNORE = "ignore";

    @Requirement
    private Logger logger;

    @Requirement
    private RepositorySystem repositorySystem;

    @Requirement
    private Map<String, TychoProject> projectTypes;

    @Requirement
    private TargetPlatformFilterConfigurationReader filterReader;

    public TargetPlatformConfiguration getTargetPlatformConfiguration(MavenSession session, MavenProject project)
            throws BuildFailureException {
        TargetPlatformConfiguration result = new TargetPlatformConfiguration();

        // Use org.eclipse.tycho:target-platform-configuration/configuration/environment, if provided
        Plugin plugin = project.getPlugin("org.eclipse.tycho:target-platform-configuration");

        if (plugin != null) {
            Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
            if (configuration != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("target-platform-configuration for " + project.toString() + ":\n"
                            + configuration.toString());
                }

                addTargetEnvironments(result, project, configuration);

                setTargetPlatformResolver(result, configuration);

                setTarget(result, session, project, configuration);

                setPomDependencies(result, configuration);

                setAllowConflictingDependencies(result, configuration);

                setDisableP2Mirrors(result, configuration);

                setExecutionEnvironment(result, configuration);
                setExecutionEnvironmentDefault(result, configuration);
                setBREEHeaderSelectionPolicy(result, configuration);
                setResolveWithEEContraints(result, configuration);

                readFilters(result, configuration);

                readDependencyResolutionConfiguration(result, configuration);

                setIncludePackedArtifacts(result, configuration);
                setTargetDefinitionIncludeSources(result, configuration);
            }
        }

        if (result.getEnvironments().isEmpty()) {
            TychoProject projectType = projectTypes.get(project.getPackaging());
            if (projectType != null) {
                TargetEnvironment env = projectType.getImplicitTargetEnvironment(project);
                if (env != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Implicit target environment for " + project.toString() + ": " + env.toString());
                    }

                    result.addEnvironment(env);
                }
            }
        }

        if (result.getEnvironments().isEmpty()) {
            // applying defaults
            logger.warn("No explicit target runtime environment configuration. Build is platform dependent.");

            // Otherwise, use project or execution properties, if provided
            Properties properties = (Properties) project.getContextValue(TychoConstants.CTX_MERGED_PROPERTIES);

            // Otherwise, use current system os/ws/nl/arch
            String os = PlatformPropertiesUtils.getOS(properties);
            String ws = PlatformPropertiesUtils.getWS(properties);
            String arch = PlatformPropertiesUtils.getArch(properties);

            result.addEnvironment(new TargetEnvironment(os, ws, arch));

            result.setImplicitTargetEnvironment(true);
        } else {
            result.setImplicitTargetEnvironment(false);
        }

        return result;
    }

    private void setIncludePackedArtifacts(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("includePackedArtifacts"));

        if (value == null) {
            return;
        }
        result.setIncludePackedArtifacts(Boolean.parseBoolean(value));
    }

    private void setTargetDefinitionIncludeSources(TargetPlatformConfiguration result, Xpp3Dom configuration)
            throws BuildFailureException {
        String value = getStringValue(configuration.getChild("targetDefinitionIncludeSource"));

        if (value == null) {
            return;
        }
        try {
            result.setTargetDefinitionIncludeSourceMode(IncludeSourceMode.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new BuildFailureException(
                    "Illegal value of <targetDefinitionIncludeSource> target platform configuration parameter: "
                            + value, e);
        }
    }

    private void readDependencyResolutionConfiguration(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        Xpp3Dom resolverDom = configuration.getChild("dependency-resolution");
        if (resolverDom == null) {
            return;
        }

        setOptionalDependencies(result, resolverDom);
        readExtraRequirements(result, resolverDom);
        readProfileProperties(result, resolverDom);

    }

    private void setOptionalDependencies(TargetPlatformConfiguration result, Xpp3Dom resolverDom) {
        String value = getStringValue(resolverDom.getChild("optionalDependencies"));

        if (value == null) {
            return;
        } else if (OPTIONAL_RESOLUTION_REQUIRE.equals(value)) {
            result.setOptionalResolutionAction(OptionalResolutionAction.REQUIRE);
        } else if (OPTIONAL_RESOLUTION_IGNORE.equals(value)) {
            result.setOptionalResolutionAction(OptionalResolutionAction.IGNORE);
        } else {
            throw new BuildFailureException("Illegal value of <optionalDependencies> dependency resolution parameter: "
                    + value);
        }
    }

    protected void readExtraRequirements(TargetPlatformConfiguration result, Xpp3Dom resolverDom)
            throws BuildFailureException {
        Xpp3Dom requirementsDom = resolverDom.getChild("extraRequirements");
        if (requirementsDom == null) {
            return;
        }

        for (Xpp3Dom requirementDom : requirementsDom.getChildren("requirement")) {
            Dependency d = new Dependency();
            if (requirementDom.getChild("type") == null) {
                throw new BuildFailureException(
                        "Element <type> is missing in <extraRequirements><requirement> section.");
            }
            if (requirementDom.getChild("id") == null) {
                throw new BuildFailureException("Element <id> is missing in <extraRequirements><requirement> section.");
            }
            if (requirementDom.getChild("versionRange") == null) {
                throw new BuildFailureException(
                        "Element <versionRange> is missing in <extraRequirements><requirement> section.");
            }
            d.setType(requirementDom.getChild("type").getValue());
            d.setArtifactId(requirementDom.getChild("id").getValue());
            d.setVersion(requirementDom.getChild("versionRange").getValue());
            result.addExtraRequirement(d);
        }
    }

    private void readProfileProperties(TargetPlatformConfiguration result, Xpp3Dom resolverDom) {
        Xpp3Dom propertiesDom = resolverDom.getChild("profileProperties");
        if (propertiesDom == null) {
            return;
        }

        Xpp3Dom[] propertyDomList = propertiesDom.getChildren();
        for (Xpp3Dom propertyDom : propertyDomList) {
            result.addProfileProperty(propertyDom.getName(), propertyDom.getValue().trim());
        }
    }

    private void setExecutionEnvironment(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("executionEnvironment"));

        if (value == null) {
            return;
        }
        result.setExecutionEnvironment(value);
    }

    private void setExecutionEnvironmentDefault(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("executionEnvironmentDefault"));

        if (value == null) {
            return;
        }
        result.setExecutionEnvironmentDefault(value);
    }

    private void setBREEHeaderSelectionPolicy(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("breeHeaderSelectionPolicy"));

        if (value == null) {
            return;
        }
        try {
            result.setBREEHeaderSelectionPolicy(BREEHeaderSelectionPolicy.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Illegal value of <breeHeaderSelectionPolicy> target platform parameter: "
                    + value);
        }
    }

    /**
     * Take the constraints of the configured execution environment into account when resolving
     * dependencies or target definitions. These constraints include the list of system packages and
     * the <tt>Bundle-RequiredExecutionEnvironment</tt> header. When set to <code>true</code>, the
     * dependency resolution verifies that the bundle and all required bundles can be used in an
     * OSGi container with the configured execution environment.
     */
    private void setResolveWithEEContraints(TargetPlatformConfiguration result, Xpp3Dom resolverDom) {
        String value = getStringValue(resolverDom.getChild("resolveWithExecutionEnvironmentConstraints"));
        if (value == null) {
            return;
        }
        result.setResolveWithEEContraints(Boolean.valueOf(value));
    }

    private void setDisableP2Mirrors(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        Xpp3Dom disableP2mirrorsDom = configuration.getChild("disableP2Mirrors");
        if (disableP2mirrorsDom != null) {
            logger.warn("Unsupported target-platform-configuration <disableP2Mirrors>. Use tycho.disableP2Mirrors -D command line parameter or settings.xml property.");
        }
    }

    private void setAllowConflictingDependencies(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("allowConflictingDependencies"));

        if (value == null) {
            return;
        }
        result.setAllowConflictingDependencies(Boolean.parseBoolean(value));
    }

    private void addTargetEnvironments(TargetPlatformConfiguration result, MavenProject project, Xpp3Dom configuration) {
        try {
            TargetEnvironment deprecatedTargetEnvironmentSpec = getDeprecatedTargetEnvironment(configuration);
            if (deprecatedTargetEnvironmentSpec != null) {
                result.addEnvironment(deprecatedTargetEnvironmentSpec);
            }

            Xpp3Dom environmentsDom = configuration.getChild("environments");
            if (environmentsDom != null) {
                if (deprecatedTargetEnvironmentSpec != null) {
                    String message = "Deprecated target-platform-configuration <environment> element must not be combined with new <environments> element; check the (inherited) configuration of "
                            + project.getId();
                    throw new RuntimeException(message);
                }
                for (Xpp3Dom environmentDom : environmentsDom.getChildren("environment")) {
                    result.addEnvironment(newTargetEnvironment(environmentDom));
                }
            }
        } catch (TargetPlatformConfigurationException e) {
            throw new RuntimeException("target-platform-configuration error in project " + project.getId(), e);
        }
    }

    protected TargetEnvironment getDeprecatedTargetEnvironment(Xpp3Dom configuration)
            throws TargetPlatformConfigurationException {
        Xpp3Dom environmentDom = configuration.getChild("environment");
        if (environmentDom != null) {
            logger.warn("target-platform-configuration <environment> element is deprecated; use <environments> instead");
            return newTargetEnvironment(environmentDom);
        }
        return null;
    }

    private void setPomDependencies(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("pomDependencies"));

        if (value == null) {
            return;
        }
        result.setPomDependencies(value);
    }

    private void setTarget(TargetPlatformConfiguration result, MavenSession session, MavenProject project,
            Xpp3Dom configuration) {
        Xpp3Dom targetDom = configuration.getChild("target");
        if (targetDom == null) {
            return;
        }

        Xpp3Dom[] artifactDomArray = targetDom.getChildren("artifact");
        if (artifactDomArray == null || artifactDomArray.length == 0) {
            return;
        }

        for (Xpp3Dom artifactDom : artifactDomArray) {
            addTargetArtifact(result, session, project, artifactDom);
        }
    }

    protected void addTargetArtifact(TargetPlatformConfiguration result, MavenSession session, MavenProject project,
            Xpp3Dom artifactDom) {
        Xpp3Dom groupIdDom = artifactDom.getChild("groupId");
        Xpp3Dom artifactIdDom = artifactDom.getChild("artifactId");
        Xpp3Dom versionDom = artifactDom.getChild("version");
        if (groupIdDom == null || artifactIdDom == null || versionDom == null) {
            throw new BuildFailureException(
                    "The target artifact configuration is invalid - <groupId>, <artifactId> and <version> are mandatory");
        }
        Xpp3Dom classifierDom = artifactDom.getChild("classifier");

        String groupId = groupIdDom.getValue();
        String artifactId = artifactIdDom.getValue();
        String version = versionDom.getValue();
        String classifier = classifierDom != null ? classifierDom.getValue() : null;

        File targetFile = null;
        for (MavenProject otherProject : session.getProjects()) {
            if (groupId.equals(otherProject.getGroupId()) && artifactId.equals(otherProject.getArtifactId())
                    && version.equals(otherProject.getVersion())) {
                String fileName;
                if (classifier == null) {
                    // no classifier means target is provided using packaging type eclipse-target-definition
                    fileName = artifactId;
                } else {
                    // backward compat for target files manually installed via build-helper-maven-plugin
                    fileName = classifier;
                }
                targetFile = new File(otherProject.getBasedir(), fileName + ".target");
                break;
            }
        }

        if (targetFile == null) {
            Artifact artifact = repositorySystem.createArtifactWithClassifier(groupId, artifactId, version, "target",
                    classifier);
            ArtifactResolutionRequest request = new ArtifactResolutionRequest();
            request.setArtifact(artifact);
            request.setLocalRepository(session.getLocalRepository());
            request.setRemoteRepositories(project.getRemoteArtifactRepositories());
            repositorySystem.resolve(request);

            if (!artifact.isResolved()) {
                throw new RuntimeException("Could not resolve target platform specification artifact " + artifact);
            }

            targetFile = artifact.getFile();
        }

        result.addTarget(targetFile);
    }

    private void setTargetPlatformResolver(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        String value = getStringValue(configuration.getChild("resolver"));

        if (value == null) {
            return;
        }
        result.setResolver(value);
    }

    private void readFilters(TargetPlatformConfiguration result, Xpp3Dom configuration) {
        Xpp3Dom filtersDom = configuration.getChild("filters");
        if (filtersDom != null) {
            result.setFilters(filterReader.parseFilterConfiguration(filtersDom));
        }
    }

    private static TargetEnvironment newTargetEnvironment(Xpp3Dom environmentDom)
            throws TargetPlatformConfigurationException {
        Xpp3Dom osDom = environmentDom.getChild("os");
        if (osDom == null) {
            String message = "<os> element is missing within target-platform-configuration (element <environment>)";
            throw new TargetPlatformConfigurationException(message);
        }

        Xpp3Dom wsDom = environmentDom.getChild("ws");
        if (wsDom == null) {
            String message = "<ws> element is missing within target-platform-configuration (element <environment>)";
            throw new TargetPlatformConfigurationException(message);
        }

        Xpp3Dom archDom = environmentDom.getChild("arch");
        if (archDom == null) {
            String message = "<arch> element is missing within target-platform-configuration (element <environment>)";
            throw new TargetPlatformConfigurationException(message);
        }

        return new TargetEnvironment(osDom.getValue(), wsDom.getValue(), archDom.getValue());
    }

    /**
     * Returns the string value of the given node, with all "value not set" cases normalized to
     * <code>null</code>.
     */
    private static String getStringValue(Xpp3Dom element) {
        if (element == null) {
            return null;
        }

        String value = element.getValue().trim();
        if ("".equals(value)) {
            return null;
        } else {
            return value;
        }
    }

}
