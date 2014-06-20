// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.view;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.FileTarget;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.LoadedPackageProvider;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.syntax.FilesetEntry;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.ConfiguredRuleClassProvider.PrerequisiteValidator;
import com.google.devtools.build.lib.view.PrerequisiteMap.Prerequisite;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.ConfiguredFilesetEntry;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.actions.ActionConstructionContext;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.view.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.view.fileset.FilesetProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A helper class for rule implementations building and initialization. Objects of this
 * class are intended to be passed to the builder for the configured target, which then creates the
 * configured target.
 */
public final class RuleContext extends TargetContext
    implements ActionConstructionContext, ActionRegistry, RuleErrorConsumer {
  private final Rule rule;
  private final ListMultimap<String, TransitiveInfoCollection> targetMap;
  private final ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap;
  private final AttributeMap attributes;

  private ActionOwner actionOwner;

  private RuleContext(Builder builder, ListMultimap<String, TransitiveInfoCollection> targetMap,
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap) {
    super(builder.env, builder.rule, builder.configuration, builder.prerequisites,
        builder.visibility);
    this.rule = builder.rule;
    this.targetMap = targetMap;
    this.attributes = new ConfiguredAttributeMapper(builder.rule, builder.configuration);
    this.filesetEntryMap = filesetEntryMap;
  }

  @Override
  public Rule getRule() {
    return rule;
  }

  /**
   * Accessor for the Rule's attribute values.
   */
  public AttributeMap attributes() {
    return attributes;
  }

  /**
   * Returns whether this instance is known to have errors at this point during analysis. Do not
   * call this method after the initializationHook has returned.
   */
  public boolean hasErrors() {
    return getAnalysisEnvironment().hasErrors();
  }

  /**
   * Returns an immutable map from attribute name to list of configured targets for that attribute.
   */
  public ListMultimap<String, TransitiveInfoCollection> getConfiguredTargetMap() {
    return targetMap;
  }

  /**
   * Returns an immutable map from attribute name to list of fileset entries.
   */
  public ListMultimap<String, ConfiguredFilesetEntry> getFilesetEntryMap() {
    return filesetEntryMap;
  }

  @Override
  public ActionOwner getActionOwner() {
    if (actionOwner == null) {
      actionOwner = new RuleActionOwner(rule, getConfiguration());
    }
    return actionOwner;
  }

  /**
   * Returns a configuration fragment for this this target.
   */
  @Nullable
  public <T extends Fragment> T getFragment(Class<T> fragment) {
    return getConfiguration().getFragment(fragment);
  }

  @Override
  public ArtifactOwner getOwner() {
    return getAnalysisEnvironment().getOwner();
  }

  private static final class RuleActionOwner implements ActionOwner {
    private final Rule rule;
    private final BuildConfiguration configuration;

    private RuleActionOwner(Rule rule, BuildConfiguration configuration) {
      this.rule = rule;
      this.configuration = configuration;
    }

    @Override
    public Location getLocation() {
      return rule.getLocation();
    }

    @Override
    public Label getLabel() {
      return rule.getLabel();
    }

    @Override
    public String getConfigurationName() {
      return configuration.getShortName();
    }

    @Override
    public String getConfigurationMnemonic() {
      return configuration.getMnemonic();
    }

    @Override
    public String getConfigurationShortCacheKey() {
      return configuration.shortCacheKey();
    }

    @Override
    public String getTargetKind() {
      return rule.getTargetKind();
    }

    @Override
    public String getAdditionalProgressInfo() {
      return configuration.isHostConfiguration() ? "host" : null;
    }
  }

  @Override
  public void registerAction(Action action) {
    getAnalysisEnvironment().registerAction(action);
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * errors in the current rule.
   */
  @Override
  public void ruleError(String message) {
    reportError(rule.getLocation(), prefixRuleMessage(message));
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * warnings in the current rule.
   */
  @Override
  public void ruleWarning(String message) {
    reportWarning(rule.getLocation(), prefixRuleMessage(message));
  }

  /**
   * Convenience function for subclasses to report attribute-specific errors in
   * the current rule.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeError(String attrName, String message) {
    reportError(rule.getAttributeLocation(attrName),
                prefixAttributeMessage(Attribute.isImplicit(attrName)
                                           ? "(an implicit dependency)"
                                           : attrName,
                                       message));
  }

  /**
   * Like attributeError, but does not mark the configured target as errored.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeWarning(String attrName, String message) {
    reportWarning(rule.getAttributeLocation(attrName),
                  prefixAttributeMessage(Attribute.isImplicit(attrName)
                                             ? "(an implicit dependency)"
                                             : attrName,
                                         message));
  }

  private String prefixAttributeMessage(String attrName, String message) {
    return "in " + attrName + " attribute of "
           + rule.getRuleClass() + " rule "
           + getLabel() + ": " + message;
  }

  private String prefixRuleMessage(String message) {
    return "in " + rule.getRuleClass() + " rule "
           + getLabel() + ": " + message;
  }

  private void reportError(Location location, String message) {
    getAnalysisEnvironment().getReporter().error(location, message);
  }

  private void reportWarning(Location location, String message) {
    getAnalysisEnvironment().getReporter().warn(location, message);
  }

  /**
   * Returns an artifact beneath the root of either the "bin" or "genfiles"
   * tree, whose path is based on the name of this target and the current
   * configuration.  The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Artifact createOutputArtifact() {
    return internalCreateOutputArtifact(getTarget());
  }

  /**
   * Returns the output artifact of an {@link OutputFile} of this target.
   *
   * @see #createOutputArtifact()
   */
  public Artifact createOutputArtifact(OutputFile out) {
    return internalCreateOutputArtifact(out);
  }

  /**
   * Implementation for {@link #createOutputArtifact()} and
   * {@link #createOutputArtifact(OutputFile)}. This is private so that
   * {@link #createOutputArtifact(OutputFile)} can have a more specific
   * signature.
   */
  private Artifact internalCreateOutputArtifact(Target target) {
    Root root = getBinOrGenfilesDirectory();
    return getAnalysisEnvironment().getDerivedArtifact(Util.getWorkspaceRelativePath(target), root);
  }

  /**
   * Returns the root of either the "bin" or "genfiles"
   * tree, based on this target and the current configuration.
   * The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Root getBinOrGenfilesDirectory() {
    return rule.hasBinaryOutput()
        ? getConfiguration().getBinDirectory()
        : getConfiguration().getGenfilesDirectory();
  }

  /**
   * Returns the list of transitive info collections that feed into this target through the
   * specified attribute. Note that you need to specify the correct mode for the attribute,
   * otherwise an assertion will be raised.
   */
  public List<TransitiveInfoCollection> getPrerequisites(String attributeName, Mode mode) {
    checkAttribute(attributeName, mode);
    return targetMap.get(attributeName);
  }

  /**
   * Returns the specified provider of the prerequisite referenced by the attribute in the
   * argument. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. If the attribute is empty of it does not support the specified
   * provider, returns null.
   */
  public <C extends TransitiveInfoProvider> C getPrerequisite(
      String attributeName, Mode mode, Class<C> provider) {
    TransitiveInfoCollection prerequisite = internalGetPrerequisite(attributeName, mode);
    if (prerequisite != null) {
      return prerequisite.getProvider(provider);
    } else {
      return null;
    }
  }

  /**
   * Returns the transitive info collection that feeds into this target through the specified
   * attribute. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. Returns null if the attribute is empty.
   */
  public TransitiveInfoCollection getPrerequisite(String attributeName, Mode mode) {
    return internalGetPrerequisite(attributeName, mode);
  }

  private TransitiveInfoCollection internalGetPrerequisite(String attributeName, Mode mode) {
    Attribute attributeDefinition = rule.getAttributeDefinition(attributeName);
    if ((attributeDefinition != null) && !(attributeDefinition.getType() == Type.LABEL ||
        // This function is applicable to LABEL_LIST attributes too, since
        // there are a lot of rules with LABEL_TYPE srcs attributes which allow only one Artifact.
        attributeDefinition.getType() == Type.LABEL_LIST)) {
      throw new IllegalStateException(rule.getRuleClass() + " attribute " + attributeName
        + " is not a label type attribute");
    }
    List<TransitiveInfoCollection> elements = unmodifiablePrerequisites(attributeName, mode);
    if (Iterables.size(elements) > 1) {
      throw new IllegalStateException(rule.getRuleClass() + " attribute " + attributeName
          + " produces more then one prerequisites");
    }
    return elements.isEmpty() ? null : Iterables.getOnlyElement(elements);
  }

  /**
   * For the specified attribute "attributeName" (which must be of type list(label), resolves all
   * the labels into ConfiguredTargets (for the configuration appropriate to the attribute) and
   * returns them as an immutable list. If no attribute with that name exists, it returns an empty
   * list.
   */
  private List<TransitiveInfoCollection> unmodifiablePrerequisites(String attributeName,
                                                                   Mode mode) {
    checkAttribute(attributeName, mode);
    return targetMap.get(attributeName);
  }

  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file.
   */
  public <C extends TransitiveInfoProvider> Iterable<C> getPrerequisites(String attributeName,
      Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    checkAttribute(attributeName, mode);
    return AnalysisUtils.getProviders(targetMap.get(attributeName), classType);
  }

  /**
   * Returns the prerequisite referred to by the specified attribute. Also checks whether
   * the attribute is marked as executable and that the target referred to can actually be
   * executed.
   *
   * <p>The {@code mode} argument must match the configuration transition specified in the
   * definition of the attribute.
   *
   * @param attributeName the name of the attribute
   * @param mode the configuration transition of the attribute
   *
   * @return the {@link FilesToRunProvider} interface of the prerequisite.
   */
  public FilesToRunProvider getExecutablePrerequisite(String attributeName, Mode mode) {
    Attribute ruleDefinition = getRule().getAttributeDefinition(attributeName);

    if (ruleDefinition == null) {
      throw new IllegalStateException(getRule().getRuleClass() + " attribute " + attributeName
          + " is not defined");
    }
    if (!ruleDefinition.isExecutable()) {
      throw new IllegalStateException(getRule().getRuleClass() + " attribute " + attributeName
          + " is not configured to be executable");
    }

    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    if (prerequisite == null) {
      return null;
    }

    FilesToRunProvider result = prerequisite.getProvider(FilesToRunProvider.class);
    if (result == null || result.getExecutable() == null) {
      attributeError(
          attributeName, prerequisite.getLabel() + " does not refer to a valid executable target");
    }
    return result;
  }

  /**
   * Gets an attribute of type STRING_LIST expanding Make variables and
   * tokenizes the result.
   *
   * @param attributeName the name of the attribute to process
   * @return a list of strings containing the expanded and tokenized values for the
   *         attribute
   */
  public List<String> getTokenizedStringListAttr(String attributeName) {
    if (!getRule().isAttrDefined(attributeName, Type.STRING_LIST)) {
      // TODO(bazel-team): This should be an error.
      return ImmutableList.of();
    }
    List<String> original = attributes().get(attributeName, Type.STRING_LIST);
    if (original.isEmpty()) {
      return ImmutableList.of();
    }
    List<String> tokens = new ArrayList<>();
    for (String token : original) {
      tokenizeAndExpandMakeVars(tokens, attributeName, token);
    }
    return ImmutableList.copyOf(tokens);
  }

  /**
   * Expands make variables in value and tokenizes the result into tokens.
   *
   * <p>This methods should be called only during initialization.
   */
  public void tokenizeAndExpandMakeVars(List<String> tokens, String attributeName,
                                        String value) {
    try {
      ShellUtils.tokenize(tokens, expandMakeVariables(attributeName, value));
    } catch (ShellUtils.TokenizationException e) {
      attributeError(attributeName, e.getMessage());
    }
  }

  /**
   * Returns the string "expression" after expanding all embedded references to
   * "Make" variables.  If any errors are encountered, they are reported, and
   * "expression" is returned unchanged.
   *
   * @param attributeName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @return the expansion of "expression".
   */
  public String expandMakeVariables(String attributeName, String expression) {
    return expandMakeVariables(attributeName, expression,
        new ConfigurationMakeVariableContext(getRule().getPackage(), getConfiguration()));
  }

  /**
   * Returns the string "expression" after expanding all embedded references to
   * "Make" variables.  If any errors are encountered, they are reported, and
   * "expression" is returned unchanged.
   *
   * @param attributeName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @param context the ConfigurationMakeVariableContext which can have a customized
   *     lookupMakeVariable(String) method.
   * @return the expansion of "expression".
   */
  public String expandMakeVariables(String attributeName, String expression,
      ConfigurationMakeVariableContext context) {
    try {
      return MakeVariableExpander.expand(expression, context);
    } catch (MakeVariableExpander.ExpansionException e) {
      attributeError(attributeName, e.getMessage());
      return expression;
    }
  }

  /**
   * Gets the value of the STRING_LIST attribute expanding all make variables.
   */
  public List<String> expandedMakeVariablesList(String attrName) {
    List<String> variables = new ArrayList<>();
    for (String variable : attributes().get(attrName, Type.STRING_LIST)) {
      variables.add(expandMakeVariables(attrName, variable));
    }
    return variables;
  }

  /**
   * If the string consists of a single variable, returns the expansion of
   * that variable. Otherwise, returns null. Syntax errors are reported.
   *
   * @param attrName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @return the expansion of "expression", or null.
   */
  public String expandSingleMakeVariable(String attrName, String expression) {
    try {
      return MakeVariableExpander.expandSingleVariable(expression,
          new ConfigurationMakeVariableContext(getRule().getPackage(), getConfiguration()));
    } catch (MakeVariableExpander.ExpansionException e) {
      attributeError(attrName, e.getMessage());
      return expression;
    }
  }

  private void checkAttribute(String attributeName, Mode mode) {
    Attribute attributeDefinition = getRule().getAttributeDefinition(attributeName);
    if (attributeDefinition == null) {
      throw new IllegalStateException(getRule().getLocation() + ": " + getRule().getRuleClass()
        + " attribute " + attributeName + " is not defined");
    }
    if (mode == Mode.HOST) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.HOST) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRule().getRuleClass() + " attribute " + attributeName
            + " is not configured for the host configuration");
      }
    } else if (mode == Mode.TARGET) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.NONE) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRule().getRuleClass() + " attribute " + attributeName
            + " is not configured for the target configuration");
      }
    } else if (mode == Mode.DATA) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.DATA) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRule().getRuleClass() + " attribute " + attributeName
            + " is not configured for the data configuration");
      }
    }
  }

  /**
   * For the specified attribute "attributeName" (which must be of type
   * list(label)), resolve all the labels into ConfiguredTargets (for the
   * configuration appropriate to the attribute) and return their build
   * artifacts as an immutable list.
   *
   * @param attributeName the name of the attribute to traverse
   */
  public ImmutableList<Artifact> getPrerequisiteArtifacts(String attributeName, Mode mode) {
    Set<Artifact> result = new LinkedHashSet<>();
    for (FileProvider target : getPrerequisites(attributeName, mode, FileProvider.class)) {
      Iterables.addAll(result, target.getFilesToBuild());
    }
    return ImmutableList.copyOf(result);
  }

  /**
   * For the specified attribute "attributeName" (which must be of type
   * list(label)), resolve all the labels into ConfiguredTargets (for the
   * configuration appropriate to the attribute) and return their build
   * artifacts as an immutable list.
   *
   * @param attributeName the name of the attribute to traverse
   * @param fileTypes the types of files to return
   */
  public ImmutableList<Artifact> getPrerequisiteArtifacts(
      String attributeName, Mode mode, FileTypeSet fileTypes) {
    return ImmutableList.copyOf(FileType.filter(getPrerequisiteArtifacts(attributeName, mode),
        fileTypes));
  }

  /**
   * For the specified attribute "attributeName" (which must be of type
   * list(label)), resolve all the labels into ConfiguredTargets (for the
   * configuration appropriate to the attribute) and return their build
   * artifacts as an immutable list.
   *
   * @param attributeName the name of the attribute to traverse
   * @param fileType the type of files to return.
   */
  public ImmutableList<Artifact> getPrerequisiteArtifacts(
      String attributeName, Mode mode, FileType fileType) {
    return ImmutableList.copyOf(FileType.filter(getPrerequisiteArtifacts(attributeName, mode),
        fileType));
  }

  /**
   * For the specified attribute "attributeName" (which must be of type label),
   * resolves the ConfiguredTarget and returns its single build artifact.
   *
   * <p>If the attribute is optional, has no default and was not specified, then
   * null will be returned. Note also that null is returned (and an attribute
   * error is raised) if there wasn't exactly one build artifact for the target.
   */
  public Artifact getPrerequisiteArtifact(String attributeName, Mode mode) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, mode);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  /**
   * Equivalent to getPrerequisiteArtifact(), but also asserts that
   * host-configuration is appropriate for the specified attribute.
   */
  public Artifact getHostPrerequisiteArtifact(String attributeName) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, Mode.HOST);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  private Artifact transitiveInfoCollectionToArtifact(
      String attributeName, TransitiveInfoCollection target) {
    if (target != null) {
      Iterable<Artifact> artifacts = target.getProvider(FileProvider.class).getFilesToBuild();
      if (Iterables.size(artifacts) == 1) {
        return Iterables.getOnlyElement(artifacts);
      } else {
        attributeError(attributeName, target.getLabel() + " expected a single artifact");
      }
    }
    return null;
  }

  /**
   * Returns the sole file in the "srcs" attribute. Reports an error and
   * (possibly) returns null if "srcs" does not identify a single file of the
   * expected type.
   */
  public Artifact getSingleSource(String fileTypeName) {
    List<Artifact> srcs = getPrerequisiteArtifacts("srcs", Mode.TARGET);
    switch (srcs.size()) {
      case 0 : // error already issued by getSrc()
        return null;
      case 1 : // ok
        return Iterables.getOnlyElement(srcs);
      default :
        attributeError("srcs", "only a single " + fileTypeName + " is allowed here");
        return srcs.iterator().next();
    }
  }

  public Artifact getSingleSource() {
    return getSingleSource(getRule().getRuleClass() + " source file");
  }

  /**
   * Returns a path fragment qualified by the rule name and unique fragment to
   * disambiguate artifacts produced from the source file appearing in
   * multiple rules.
   *
   * <p>For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
   */
  public final PathFragment getUniqueDirectory(String fragment) {
    return AnalysisUtils.getUniqueDirectory(getLabel(), new PathFragment(fragment));
  }

  /**
   * Check that all targets that were specified as sources are from the same
   * package as this rule. Output a warning or an error for every target that is
   * imported from a different package.
   */
  public void checkSrcsSamePackage(boolean onlyWarn) {
    PathFragment packageName = getLabel().getPackageFragment();
    for (Artifact srcItem : getPrerequisiteArtifacts("srcs", Mode.TARGET)) {
      if (!srcItem.isSourceArtifact()) {
        // In theory, we should not do this check. However, in practice, we
        // have a couple of rules that do not obey the "srcs must contain
        // files and only files" rule. Thus, we are stuck with this hack here :(
        continue;
      }
      Label associatedLabel = srcItem.getOwner();
      PathFragment itemPackageName = associatedLabel.getPackageFragment();
      if (!itemPackageName.equals(packageName)) {
        String message = "please do not import '" + associatedLabel + "' directly. "
            + "You should either move the file to this package or depend on "
            + "an appropriate rule there";
        if (onlyWarn) {
          attributeWarning("srcs", message);
        } else {
          attributeError("srcs", message);
        }
      }
    }
  }


  /**
   * Returns the label to which the {@code NODEP_LABEL} attribute
   * {@code attrName} refers, checking that it is a valid label, and that it is
   * referring to a local target. Reports a warning otherwise.
   */
  public Label getLocalNodepLabelAttribute(String attrName) {
    Label label = attributes().get(attrName, Type.NODEP_LABEL);
    if (label == null) {
      return null;
    }

    if (!getTarget().getLabel().getPackageFragment().equals(label.getPackageFragment())) {
      attributeWarning(attrName, "does not reference a local rule");
    }

    return label;
  }

  /**
   * Returns the implicit output artifact for a given template function. If multiple or no artifacts
   * can be found as a result of the template, an exception is thrown.
   */
  public Artifact getImplicitOutputArtifact(ImplicitOutputsFunction function) {
    Iterable<String> result = function.apply(rule);
    String path = Iterables.getOnlyElement(result);
    Root root = getBinOrGenfilesDirectory();
    PathFragment packageFragment = getLabel().getPackageFragment();
    return getAnalysisEnvironment().getDerivedArtifact(packageFragment.getRelative(path), root);
  }

  /**
   * Convenience method to return a host configured target for the "compiler"
   * attribute. Allows caller to decide whether a warning should be printed if
   * the "compiler" attribute is not set to the default value.
   *
   * @param warnIfNotDefault if true, print a warning if the value for the
   *        "compiler" attribute is set to something other than the default
   * @return a ConfiguredTarget using the host configuration for the "compiler"
   *         attribute
   */
  public final FilesToRunProvider getCompiler(boolean warnIfNotDefault) {
    Label label = attributes().get("compiler", Type.LABEL);
    if (warnIfNotDefault && !label.equals(getRule().getAttrDefaultValue("compiler"))) {
      attributeWarning("compiler", "setting the compiler is strongly discouraged");
    }
    return getExecutablePrerequisite("compiler", Mode.HOST);
  }

  /**
   * Returns the (unmodifiable, ordered) list of artifacts which are the outputs
   * of this target.
   *
   * <p>Each element in this list is associated with a single output, either
   * declared implicitly (via setImplicitOutputsFunction()) or explicitly
   * (listed in the 'outs' attribute of our rule).
   */
  public final ImmutableList<Artifact> getOutputArtifacts() {
    ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
    for (OutputFile out : getRule().getOutputFiles()) {
      artifacts.add(createOutputArtifact(out));
    }
    return artifacts.build();
  }

  /**
   * Like getFilesToBuild(), except that it also includes the runfiles middleman, if any.
   * Middlemen are expanded in the SpawnStrategy or by the Distributor.
   */
  public static ImmutableList<Artifact> getFilesToRun(
      RunfilesSupport runfilesSupport, NestedSet<Artifact> filesToBuild) {
    if (runfilesSupport == null) {
      return ImmutableList.copyOf(filesToBuild);
    } else {
      ImmutableList.Builder<Artifact> allFilesToBuild = ImmutableList.builder();
      allFilesToBuild.addAll(filesToBuild);
      allFilesToBuild.add(runfilesSupport.getRunfilesMiddleman());
      return allFilesToBuild.build();
    }
  }

  /**
   * Like {@link #getOutputArtifacts()} but for a singular output item.
   * Reports an error if the "out" attribute is not a singleton.
   *
   * @return null if the output list is empty, the artifact for the first item
   *         of the output list otherwise
   */
  public Artifact getOutputArtifact() {
    List<Artifact> outs = getOutputArtifacts();
    if (outs.size() != 1) {
      attributeError("out", "exactly one output file required");
      if (outs.isEmpty()) {
        return null;
      }
    }
    return outs.get(0);
  }

  /**
   * Returns an artifact with a given file extension. All other path components
   * are the same as in {@code pathFragment}.
   */
  public final Artifact getRelatedArtifact(PathFragment pathFragment, String extension) {
    PathFragment file = FileSystemUtils.replaceExtension(pathFragment, extension);
    return getAnalysisEnvironment().getDerivedArtifact(file, getConfiguration().getBinDirectory());
  }

  /**
   * Returns true if runfiles support should create the runfiles tree, or
   * false if it should just create the manifest.
   */
  public boolean shouldCreateRunfilesSymlinks() {
    // TODO(bazel-team): Ideally we wouldn't need such logic, and we'd
    // always use the BuildConfiguration#buildRunfiles() to determine
    // whether to build the runfiles. The problem is that certain build
    // steps actually consume their runfiles. These include:
    //  a. par files consumes the runfiles directory
    //     We should modify autopar to take a list of files instead.
    //     of the runfiles directory.
    //  b. host tools could potentially use data files, but currently don't
    //     (they're run from the execution root, not a runfiles tree).
    //     Currently hostConfiguration.buildRunfiles() returns true.
    if (TargetUtils.isTestRule(getTarget())) {
      // Tests are only executed during testing (duh),
      // and their runfiles are generated lazily on local
      // execution (see LocalTestStrategy). Therefore, it
      // is safe not to build their runfiles.
      return getConfiguration().buildRunfiles();
    } else {
      return true;
    }
  }

  /**
   * @returns true if {@code rule} is visible from {@code prerequisite}.
   *
   * <p>This only computes the logic as implemented by the visibility system. The final decision
   * whether a dependency is allowed is made by
   * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}.
   */
  public static boolean isVisible(Rule rule, TransitiveInfoCollection prerequisite) {
    // Check visibility attribute
    for (PackageSpecification specification :
      prerequisite.getProvider(VisibilityProvider.class).getVisibility()) {
      if (specification.containsPackage(rule.getLabel().getPackageFragment())) {
        return true;
      }
    }

    return false;
  }

  public static final class Builder {
    private final AnalysisEnvironment env;
    private final LoadedPackageProvider loadedPackageProvider;
    private final Rule rule;
    private final BuildConfiguration configuration;
    private final PrerequisiteValidator prerequisiteValidator;
    private PrerequisiteMap prerequisites;
    private ListMultimap<Attribute, Label> labelMap;
    private NestedSet<PackageSpecification> visibility;

    Builder(AnalysisEnvironment env, LoadedPackageProvider loadedPackageProvider, Rule rule,
        BuildConfiguration configuration, PrerequisiteValidator prerequisiteValidator) {
      this.env = Preconditions.checkNotNull(env);
      this.loadedPackageProvider = Preconditions.checkNotNull(loadedPackageProvider);
      this.rule = Preconditions.checkNotNull(rule);
      this.configuration = Preconditions.checkNotNull(configuration);
      this.prerequisiteValidator = prerequisiteValidator;
    }

    private Prerequisite getPrerequisite(Attribute attribute, Label label) {
      Target prerequisiteTarget;
      // TODO(bazel-team): The Skyframe implementation of loadedPackageProvider calls
      // graph.update(). Since this code already runs within a ConfiguredTargetNodeBuilder, the
      // Skyframe evaluator gets reentered, which could end badly. We should pass in the
      // prerequisite target nodes from the node builder instead. [skyframe-analysis]
      try {
        prerequisiteTarget = loadedPackageProvider.getLoadedTarget(label);
      } catch (NoSuchPackageException | NoSuchTargetException e) {
        // A rule can only be analyzed if all targets in its transitive closure have loaded
        // successfully.
        throw new AssertionError(e);
      }
      BuildConfiguration toConfiguration = BuildConfigurationCollection.configureTarget(
          rule, configuration, attribute, prerequisiteTarget);

      // Input files can have null configs. See BuildConfiguration.getConfigurationForInputFile().
      return (toConfiguration == null &&
          (prerequisiteTarget instanceof Rule || prerequisiteTarget instanceof OutputFile))
          ? null
          : Preconditions.checkNotNull(prerequisites.get(label, toConfiguration),
              "%s %s %s %s", rule, attribute, label, toConfiguration);
    }

    RuleContext build() {
      ListMultimap<String, TransitiveInfoCollection> targetMap = createTargetMap();
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap = createFilesetEntryMap(rule);
      return new RuleContext(this, targetMap, filesetEntryMap);
    }

    Builder setVisibility(NestedSet<PackageSpecification> visibility) {
      this.visibility = visibility;
      return this;
    }

    /**
     * Sets the prerequisites and checks their visibility. It also generates appropriate error or
     * warning messages and sets the error flag as appropriate.
     */
    Builder setPrerequisites(PrerequisiteMap prerequisites) {
      this.prerequisites = prerequisites;
      return this;
    }

    /**
     * Sets the (Attribute --> Label) map for this rule.
     */
    Builder setLabelMap(ListMultimap<Attribute, Label> labelMap) {
      this.labelMap = labelMap;
      return this;
    }

    /**
     * A builder class for the target map.
     *
     * <p>This class is also responsible for proxying providers (if requested).
     */
    private static final class TargetMapBuilder {

      private ImmutableSortedKeyListMultimap.Builder<String, TransitiveInfoCollection> builder =
          ImmutableSortedKeyListMultimap.builder();

      public TargetMapBuilder() {
      }

      public void put(String attr, TransitiveInfoCollection target) {
        builder.put(attr, target);
      }

      public ListMultimap<String, TransitiveInfoCollection> build() {
        return builder.build();
      }
    }

    private boolean validateFilesetEntry(FilesetEntry filesetEntry, Prerequisite src) {
      if (src.getTransitiveInfoCollection().getProvider(FilesetProvider.class) != null) {
        return true;
      }
      if (filesetEntry.isSourceFileset()) {
        return true;
      }

      Target srcTarget = src.getTarget();
      if (!(srcTarget instanceof FileTarget)) {
        attributeError("entries", String.format(
            "Invalid 'srcdir' target '%s'. Must be another Fileset or package",
            srcTarget.getLabel()));
        return false;
      }

      if (srcTarget instanceof OutputFile) {
        attributeWarning("entries", String.format("'srcdir' target '%s' is not an input file. " +
            "This forces the Fileset to be executed unconditionally",
            srcTarget.getLabel()));
      }

      return true;
    }

    /**
     * Determines and returns a map from attribute name to list of configured fileset entries, based
     * on a PrerequisiteMap instance.
     */
    private ListMultimap<String, ConfiguredFilesetEntry> createFilesetEntryMap(
        final Rule rule) {
      final ImmutableSortedKeyListMultimap.Builder<String, ConfiguredFilesetEntry> mapBuilder =
          ImmutableSortedKeyListMultimap.builder();
      for (Attribute attr : rule.getAttributes()) {
        if (attr.getType() != Type.FILESET_ENTRY_LIST) {
          continue;
        }
        String attributeName = attr.getName();
        List<FilesetEntry> entries = ConfiguredAttributeMapper.of(rule, configuration)
            .get(attributeName, Type.FILESET_ENTRY_LIST);
        for (FilesetEntry entry : entries) {
          if (entry.getFiles() == null) {
            Label label = entry.getSrcLabel();
            Prerequisite src = getPrerequisite(attr, label);
            if (!validateFilesetEntry(entry, src)) {
              continue;
            }

            mapBuilder.put(attributeName,
                new ConfiguredFilesetEntry(entry, src.getTransitiveInfoCollection()));
          } else {
            ImmutableList.Builder<TransitiveInfoCollection> files = ImmutableList.builder();
            for (Label file : entry.getFiles()) {
              files.add(getPrerequisite(attr, file).getTransitiveInfoCollection());
            }
            mapBuilder.put(attributeName, new ConfiguredFilesetEntry(entry, files.build()));
          }
        }
      }
      return mapBuilder.build();
    }

    /**
     * Determines and returns a map from attribute name to list of configured targets.
     */
    private ListMultimap<String, TransitiveInfoCollection> createTargetMap() {
      TargetMapBuilder mapBuilder = new TargetMapBuilder();

      for (Map.Entry<Attribute, Collection<Label>> entry : labelMap.asMap().entrySet()) {
        Attribute attribute = entry.getKey();
        if (attribute.isSilentRuleClassFilter()) {
          Predicate<RuleClass> filter = attribute.getAllowedRuleClassesPredicate();
          for (Label label : entry.getValue()) {
            Prerequisite prerequisite = getPrerequisite(attribute, label);
            Target prerequisiteTarget = prerequisite.getTarget();
            if ((prerequisiteTarget instanceof Rule) &&
                filter.apply(((Rule) prerequisiteTarget).getRuleClassObject())) {
              validateDirectPrerequisite(attribute, prerequisite);
              mapBuilder.put(attribute.getName(), prerequisite.getTransitiveInfoCollection());
            }
          }
        } else {
          for (Label label : entry.getValue()) {
            Prerequisite prerequisite = getPrerequisite(attribute, label);
            if (prerequisite != null) {
              validateDirectPrerequisite(attribute, prerequisite);
              mapBuilder.put(attribute.getName(), prerequisite.getTransitiveInfoCollection());
            }
          }
        }
      }

      // Handle abi_deps+deps error.
      Attribute abiDepsAttr = rule.getAttributeDefinition("abi_deps");
      if ((abiDepsAttr != null) && rule.isAttributeValueExplicitlySpecified("abi_deps")
          && rule.isAttributeValueExplicitlySpecified("deps")) {
        attributeError("deps", "Only one of deps and abi_deps should be provided");
      }
      return mapBuilder.build();
    }

    private String prefixRuleMessage(String message) {
      return String.format("in %s rule %s: %s", rule.getRuleClass(), rule.getLabel(), message);
    }

    private String maskInternalAttributeNames(String name) {
      return Attribute.isImplicit(name) ? "(an implicit dependency)" : name;
    }

    private String prefixAttributeMessage(String attrName, String message) {
      return String.format("in %s attribute of %s rule %s: %s",
          maskInternalAttributeNames(attrName), rule.getRuleClass(), rule.getLabel(), message);
    }

    public void reportError(Location location, String message) {
      env.getReporter().error(location, message);
    }

    public void ruleError(String message) {
      reportError(rule.getLocation(), prefixRuleMessage(message));
    }

    public void attributeError(String attrName, String message) {
      reportError(rule.getAttributeLocation(attrName), prefixAttributeMessage(attrName, message));
    }

    public void reportWarning(Location location, String message) {
      env.getReporter().warn(location, message);
    }

    public void ruleWarning(String message) {
      env.getReporter().warn(rule.getLocation(), prefixRuleMessage(message));
    }

    public void attributeWarning(String attrName, String message) {
      reportWarning(rule.getAttributeLocation(attrName), prefixAttributeMessage(attrName, message));
    }

    private void reportBadPrerequisite(Attribute attribute, String targetKind,
        Label prerequisiteLabel, String reason, boolean isWarning) {
      String msgPrefix = targetKind != null ? targetKind + " " : "";
      String msgReason = reason != null ? " (" + reason + ")" : "";
      if (isWarning) {
        attributeWarning(attribute.getName(), String.format(
            "%s'%s' is unexpected here%s; continuing anyway",
            msgPrefix, prerequisiteLabel, msgReason));
      } else {
        attributeError(attribute.getName(), String.format(
            "%s'%s' is misplaced here%s.", msgPrefix, prerequisiteLabel, msgReason));
      }
    }

    private void validateDirectPrerequisiteType(Prerequisite prerequisite, Attribute attribute) {
      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();

      if (prerequisiteTarget instanceof Rule) {
        Rule prerequisiteRule = (Rule) prerequisiteTarget;

        String reason = attribute.getValidityPredicate().checkValid(rule, prerequisiteRule);
        if (reason != null) {
          reportBadPrerequisite(attribute, prerequisiteTarget.getTargetKind(),
              prerequisiteLabel, reason, false);
        }
      }

      if (attribute.isStrictLabelCheckingEnabled()) {
        if (prerequisiteTarget instanceof Rule) {
          RuleClass ruleClass = ((Rule) prerequisiteTarget).getRuleClassObject();
          if (!attribute.getAllowedRuleClassesPredicate().apply(ruleClass)) {
            boolean allowedWithWarning = attribute.getAllowedRuleClassesWarningPredicate()
                .apply(ruleClass);
            reportBadPrerequisite(attribute, prerequisiteTarget.getTargetKind(), prerequisiteLabel,
                "expected " + attribute.getAllowedRuleClassesPredicate().toString(),
                allowedWithWarning);
          }
        } else if (prerequisiteTarget instanceof FileTarget) {
          if (!attribute.getAllowedFileTypesPredicate()
              .apply(((FileTarget) prerequisiteTarget).getFilename())) {
            if (prerequisiteTarget instanceof InputFile
                && !((InputFile) prerequisiteTarget).getPath().exists()) {
              // Misplaced labels, no corresponding target exists
              if (attribute.getAllowedFileTypesPredicate().isNone()
                  && !((InputFile) prerequisiteTarget).getFilename().contains(".")) {
                // There are no allowed files in the attribute but it's not a valid rule,
                // and the filename doesn't contain a dot --> probably a misspelled rule
                attributeError(attribute.getName(),
                    "rule '" + prerequisiteLabel + "' does not exist");
              } else {
                attributeError(attribute.getName(),
                    "target '" + prerequisiteLabel + "' does not exist");
              }
            } else {
              // The file exists but has a bad extension
              reportBadPrerequisite(attribute, "file", prerequisiteLabel,
                  "expected " + attribute.getAllowedFileTypesPredicate().toString(), false);
            }
          }
        }
      }
    }

    public Rule getRule() {
      return rule;
    }

    public BuildConfiguration getConfiguration() {
      return configuration;
    }

    /**
     * @returns true if {@code rule} is visible from {@code prerequisite}.
     *
     * <p>This only computes the logic as implemented by the visibility system. The final decision
     * whether a dependency is allowed is made by
     * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}, who is supposed to call this
     * method to determine whether a dependency is allowed as per visibility rules.
     */
    public boolean isVisible(TransitiveInfoCollection prerequisite) {
      return RuleContext.isVisible(rule, prerequisite);
    }

    private void validateDirectPrerequisiteFileTypes(Prerequisite prerequisite,
        Attribute attribute) {
      if (attribute.isSkipAnalysisTimeFileTypeCheck()) {
        return;
      }
      FileTypeSet allowedFileTypes = attribute.getAllowedFileTypesPredicate();
      if (allowedFileTypes == FileTypeSet.ANY_FILE && !attribute.isNonEmpty()
          && !attribute.isSingleArtifact()) {
        return;
      }

      TransitiveInfoCollection element = prerequisite.getTransitiveInfoCollection();
      // If we allow any file we still need to check if there are actually files generated
      // Note that this check only runs for ANY_FILE predicates if the attribute is NON_EMPTY
      // or SINGLE_ARTIFACT
      // If we performed this check when allowedFileTypes == NO_FILE this would
      // always throw an error in those cases
      if (allowedFileTypes != FileTypeSet.NO_FILE) {
        Iterable<Artifact> artifacts = element.getProvider(FileProvider.class).getFilesToBuild();
        if (attribute.isSingleArtifact() && Iterables.size(artifacts) != 1) {
          attributeError(attribute.getName(),
              "'" + element.getLabel() + "' must produce a single file");
          return;
        }
        for (Artifact sourceArtifact : artifacts) {
          if (allowedFileTypes.apply(sourceArtifact.getFilename())) {
            return;
          }
        }
        attributeError(attribute.getName(), "'" + element.getLabel() + "' does not produce any "
            + rule.getRuleClass() + " " + attribute.getName() + " files (expected "
            + allowedFileTypes + ")");
      }
    }

    private void validateDirectPrerequisite(Attribute attribute, Prerequisite prerequisite) {
      validateDirectPrerequisiteType(prerequisite, attribute);
      validateDirectPrerequisiteFileTypes(prerequisite, attribute);
      prerequisiteValidator.validate(this, prerequisite, attribute);
    }
  }
}