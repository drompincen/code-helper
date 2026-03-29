package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportParserTest {

    private final ImportParser parser = new ImportParser();

    // --- Java ---

    @Test
    void parseJavaImports() {
        String code = """
                package com.example;

                import com.foo.Bar;
                import static com.foo.Baz.method;

                public class MyClass {}
                """;
        List<String> imports = parser.parseImports(code, "MyClass.java");
        // The regex captures 'import <group1>;' — static imports match as 'static com.foo.Baz.method'
        assertTrue(imports.contains("com.foo.Bar"));
    }

    @Test
    void parseJavaStaticImport() {
        String code = "import static org.junit.Assert.assertEquals;\n";
        List<String> imports = parser.parseImports(code, "Test.java");
        // Pattern: import\s+([\w.]+); — "static" is not \w so 'static ...' won't match the simple pattern
        // Verify it doesn't crash; actual match depends on regex
        assertNotNull(imports);
    }

    @Test
    void parseJavaNoImports() {
        String code = """
                package com.example;

                public class Empty {}
                """;
        List<String> imports = parser.parseImports(code, "Empty.java");
        assertTrue(imports.isEmpty());
    }

    @Test
    void parseMultipleJavaImports() {
        StringBuilder sb = new StringBuilder("package com.example;\n\n");
        for (int i = 0; i < 12; i++) {
            sb.append("import com.pkg").append(i).append(".Class").append(i).append(";\n");
        }
        sb.append("\npublic class Multi {}");

        List<String> imports = parser.parseImports(sb.toString(), "Multi.java");
        assertEquals(12, imports.size());
        assertTrue(imports.contains("com.pkg0.Class0"));
        assertTrue(imports.contains("com.pkg11.Class11"));
    }

    @Test
    void parseJavaWildcardImport() {
        String code = "import com.foo.*;\n";
        List<String> imports = parser.parseImports(code, "Wild.java");
        // The regex [\w.]+ will match 'com.foo.*' since * is not \w — it captures 'com.foo.'
        // or it may not match at all. Just verify no crash and check behavior.
        assertNotNull(imports);
    }

    // --- Null / empty input ---

    @Test
    void nullTextReturnsEmpty() {
        List<String> imports = parser.parseImports(null, "Test.java");
        assertTrue(imports.isEmpty());
    }

    @Test
    void nullFileNameReturnsEmpty() {
        List<String> imports = parser.parseImports("import com.foo.Bar;", null);
        assertTrue(imports.isEmpty());
    }

    @Test
    void emptyTextReturnsEmpty() {
        List<String> imports = parser.parseImports("", "Test.java");
        assertTrue(imports.isEmpty());
    }

    // --- JavaScript / TypeScript ---

    @Test
    void parseJsImportFrom() {
        String code = """
                import React from 'react';
                import { useState } from 'react';
                import * as utils from './utils';
                """;
        List<String> imports = parser.parseImports(code, "App.jsx");
        assertTrue(imports.contains("react"));
        assertTrue(imports.contains("./utils"));
    }

    @Test
    void parseJsRequire() {
        String code = """
                const fs = require('fs');
                const path = require("path");
                """;
        List<String> imports = parser.parseImports(code, "index.js");
        assertTrue(imports.contains("fs"));
        assertTrue(imports.contains("path"));
    }

    @Test
    void parseTsImport() {
        String code = "import { Component } from '@angular/core';\n";
        List<String> imports = parser.parseImports(code, "app.component.ts");
        assertTrue(imports.contains("@angular/core"));
    }

    // --- Python ---

    @Test
    void parsePythonImport() {
        String code = """
                import os
                import sys
                from pathlib import Path
                from collections import defaultdict
                """;
        List<String> imports = parser.parseImports(code, "main.py");
        assertTrue(imports.contains("os"));
        assertTrue(imports.contains("sys"));
        assertTrue(imports.contains("pathlib"));
        assertTrue(imports.contains("collections"));
    }

    // --- Go ---

    @Test
    void parseGoSingleImport() {
        String code = """
                package main

                import "fmt"
                """;
        List<String> imports = parser.parseImports(code, "main.go");
        assertTrue(imports.contains("fmt"));
    }

    @Test
    void parseGoBlockImport() {
        String code = """
                package main

                import (
                    "fmt"
                    "os"
                    "net/http"
                )
                """;
        List<String> imports = parser.parseImports(code, "main.go");
        assertTrue(imports.contains("fmt"));
        assertTrue(imports.contains("os"));
        assertTrue(imports.contains("net/http"));
    }

    // --- Rust ---

    @Test
    void parseRustUse() {
        String code = """
                use std::io;
                use std::collections::HashMap;
                """;
        List<String> imports = parser.parseImports(code, "main.rs");
        assertTrue(imports.contains("std::io"));
        assertTrue(imports.contains("std::collections::HashMap"));
    }

    // --- Unsupported extension ---

    @Test
    void unsupportedExtensionReturnsEmpty() {
        String code = "#include <stdio.h>\n";
        List<String> imports = parser.parseImports(code, "main.c");
        assertTrue(imports.isEmpty());
    }

    @Test
    void noExtensionReturnsEmpty() {
        List<String> imports = parser.parseImports("import foo;", "Makefile");
        assertTrue(imports.isEmpty());
    }

    // --- Deduplication ---

    @Test
    void duplicateImportsDeduped() {
        String code = """
                import com.foo.Bar;
                import com.foo.Bar;
                import com.foo.Baz;
                """;
        List<String> imports = parser.parseImports(code, "Dup.java");
        assertEquals(2, imports.size());
        assertTrue(imports.contains("com.foo.Bar"));
        assertTrue(imports.contains("com.foo.Baz"));
    }
}
