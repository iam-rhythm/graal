/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.replacements.processor;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import jdk.graal.compiler.processor.AbstractProcessor;

public class PluginGenerator {

    private final Map<Element, List<GeneratedPlugin>> plugins;

    public PluginGenerator() {
        this.plugins = new LinkedHashMap<>();
    }

    public void addPlugin(GeneratedPlugin plugin) {
        Element topLevel = getTopLevelClass(plugin.intrinsicMethod);
        List<GeneratedPlugin> list = plugins.get(topLevel);
        if (list == null) {
            list = new ArrayList<>();
            plugins.put(topLevel, list);
        }
        list.add(plugin);
    }

    public void generateAll(AbstractProcessor processor) {
        for (Entry<Element, List<GeneratedPlugin>> entry : plugins.entrySet()) {
            disambiguateNames(entry.getValue());
            createPluginFactory(processor, entry.getKey(), entry.getValue());
        }
    }

    private static Element getTopLevelClass(Element element) {
        Element prev = element;
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null && enclosing.getKind() != ElementKind.PACKAGE) {
            prev = enclosing;
            enclosing = enclosing.getEnclosingElement();
        }
        return prev;
    }

    private static void disambiguateWith(List<GeneratedPlugin> plugins, Function<GeneratedPlugin, String> genName) {
        plugins.sort(Comparator.comparing(GeneratedPlugin::getPluginName));

        GeneratedPlugin current = plugins.get(0);
        String currentName = current.getPluginName();

        for (int i = 1; i < plugins.size(); i++) {
            GeneratedPlugin next = plugins.get(i);
            if (currentName.equals(next.getPluginName())) {
                if (current != null) {
                    current.setPluginName(genName.apply(current));
                    current = null;
                }
                next.setPluginName(genName.apply(next));
            } else {
                current = next;
                currentName = current.getPluginName();
            }
        }
    }

    private static void disambiguateNames(List<GeneratedPlugin> plugins) {
        // If we have more than one method with the same name, disambiguate with a numeric suffix.
        // We use this instead of a suffix based on argument types to mitigate hitting file name
        // length limits. We start the suffix with "__" to make it visually stick out.
        int[] nextId = {0};
        disambiguateWith(plugins, plugin -> plugin.getPluginName() + "__" + nextId[0]++);
    }

    /**
     * Map from an architecture's name as it appears in a package name to its name returned by
     * {@code jdk.vm.ci.code.Architecture.getName()}.
     */
    private static final Map<String, String> SUPPORTED_JVMCI_ARCHITECTURES;

    static {
        LinkedHashMap<String, String> supportedArchitectures = new LinkedHashMap<>();
        supportedArchitectures.put("amd64", "AMD64");
        supportedArchitectures.put("aarch64", "aarch64");
        supportedArchitectures.put("riscv64", "riscv64");
        SUPPORTED_JVMCI_ARCHITECTURES = Collections.unmodifiableMap(supportedArchitectures);
    }

    private static void createPluginFactory(AbstractProcessor processor, Element topLevelClass, List<GeneratedPlugin> plugins) {
        PackageElement pkg = (PackageElement) topLevelClass.getEnclosingElement();

        String genClassName = "PluginFactory_" + topLevelClass.getSimpleName();
        String arch = SUPPORTED_JVMCI_ARCHITECTURES.get(pkg.getSimpleName().toString());

        String qualifiedGenClassName = pkg.getQualifiedName() + "." + genClassName;
        try {
            JavaFileObject factory = processor.env().getFiler().createSourceFile(qualifiedGenClassName, topLevelClass);
            try (PrintWriter out = new PrintWriter(factory.openWriter())) {
                out.printf("// CheckStyle: stop header check\n");
                out.printf("// CheckStyle: stop line length check\n");
                out.printf("// GENERATED CONTENT - DO NOT EDIT\n");
                out.printf("// GENERATORS: %s, %s\n", ReplacementsAnnotationProcessor.class.getName(), PluginGenerator.class.getName());
                out.printf("package %s;\n", pkg.getQualifiedName());
                out.printf("\n");
                createImports(out, processor, plugins, pkg.getQualifiedName().toString());
                out.printf("\n");
                for (GeneratedPlugin plugin : plugins) {
                    plugin.generate(processor, out);
                    out.printf("\n");
                }
                if (arch != null) {
                    out.printf("public class %s implements GeneratedPluginFactory, jdk.graal.compiler.core.ArchitectureSpecific {\n", genClassName);
                    out.printf("    @Override\n");
                    out.printf("    public String getArchitecture() {\n");
                    out.printf("        return \"%s\";\n", arch);
                    out.printf("    }\n");
                } else {
                    out.printf("public class %s implements GeneratedPluginFactory {\n", genClassName);
                }
                createPluginFactoryMethod(out, plugins);
                out.printf("}\n");
            }
        } catch (IOException e) {
            processor.env().getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        }
        processor.createProviderFile(qualifiedGenClassName, "jdk.graal.compiler.nodes.graphbuilderconf.GeneratedPluginFactory", topLevelClass);
    }

    protected static void createImports(PrintWriter out, AbstractProcessor processor, List<GeneratedPlugin> plugins, String importingPackage) {
        HashSet<String> extra = new LinkedHashSet<>();

        extra.add("jdk.vm.ci.meta.ResolvedJavaMethod");
        extra.add("jdk.graal.compiler.nodes.ValueNode");
        extra.add("jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext");
        extra.add("jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin");
        extra.add("jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins");
        extra.add("jdk.graal.compiler.nodes.graphbuilderconf.GeneratedPluginFactory");
        extra.add("jdk.graal.compiler.nodes.graphbuilderconf.GeneratedPluginInjectionProvider");

        for (GeneratedPlugin plugin : plugins) {
            plugin.extraImports(processor, extra);
            extra.add("jdk.graal.compiler.nodes.graphbuilderconf." + plugin.pluginSuperclass());
            if (plugin.needsReplacement(processor)) {
                extra.add("jdk.graal.compiler.options.ExcludeFromJacocoGeneratedReport");
                if (plugin.isWithExceptionReplacement(processor)) {
                    extra.add("jdk.graal.compiler.nodes.PluginReplacementWithExceptionNode");
                } else {
                    extra.add("jdk.graal.compiler.nodes.PluginReplacementNode");
                }
            }
        }
        Pattern packageClassBoundary = Pattern.compile("\\.([A-Z])");
        out.printf("\n");
        String[] imports = extra.toArray(new String[extra.size()]);
        Arrays.sort(imports);
        for (String i : imports) {
            Matcher matcher = packageClassBoundary.matcher(i);
            if (matcher.find()) {
                String packageName = i.substring(0, matcher.start());
                String className = i.substring(matcher.start() + 1);
                if (packageName.equals(importingPackage) && className.indexOf('.') == -1) {
                    // No need to import top level class in the same package
                    continue;
                }
            }
            out.printf("import %s;\n", i);
        }
    }

    private static void createPluginFactoryMethod(PrintWriter out, List<GeneratedPlugin> plugins) {
        out.printf("    @Override\n");
        out.printf("    public void registerPlugins(InvocationPlugins plugins, GeneratedPluginInjectionProvider injection) {\n");
        for (GeneratedPlugin plugin : plugins) {
            plugin.register(out);
        }
        out.printf("    }\n");
    }
}
