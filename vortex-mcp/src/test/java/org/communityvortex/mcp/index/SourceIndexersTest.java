package org.communityvortex.mcp.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

class SourceIndexersTest {

  @Test
  void javaDeclarationsAndImports() {
    var facts = new JavaSourceIndexer().index("A.java", """
      package com.acme;
      import java.util.*;
      import static java.util.Collections.emptyList;
      import com.acme.infra.Repo;
      public record Point(int x, int y) {
        enum Kind { A }
        interface Shape {}
        @interface Marker {}
        Point { }
        static int origin = 0;
        double length(Point other) { return 0; }
      }
      """).orElseThrow();

    assertThat(facts.unit()).isEqualTo("com.acme");
    assertThat(facts.language()).isEqualTo("java");
    assertThat(facts.imports()).containsExactly("java.util", "java.util.Collections", "com.acme.infra.Repo");
    assertThat(facts.symbols()).extracting(Symbol::qualifiedName, Symbol::kind).contains(
      tuple("com.acme.Point", "record"),
      tuple("com.acme.Point.Kind", "enum"),
      tuple("com.acme.Point.Shape", "interface"),
      tuple("com.acme.Point.Marker", "annotation"),
      tuple("com.acme.Point.origin", "field"),
      tuple("com.acme.Point.length", "method"));
    assertThat(facts.symbols()).filteredOn(s -> s.name().equals("length")).first()
      .satisfies(s -> assertThat(s.signature()).isEqualTo("double length(Point other)"));
  }

  @Test
  void javaInDefaultPackageAndUnparsableSource() {
    var indexer = new JavaSourceIndexer();
    assertThat(indexer.index("A.java", "class A { A() {} }").orElseThrow().symbols())
      .extracting(Symbol::qualifiedName, Symbol::kind)
      .containsExactly(tuple("A", "class"), tuple("A.A", "constructor"));
    assertThat(indexer.index("B.java", "class {{{")).isEmpty();
    assertThat(indexer.supports("B.java")).isTrue();
    assertThat(indexer.supports("B.kt")).isFalse();
  }

  @Test
  void scriptImportsAreResolvedRelativeToTheFile() {
    var facts = new ScriptSourceIndexer().index("web/src/ui/App.tsx", """
      import React from 'react';
      import { connect } from '../db/client';
      import './styles.css';
      export * from "./widgets/index.js";
      const legacy = require('../legacy');
      const lazy = await import('./Lazy');
      export default async function App() {}
      export const VERSION = '1';
      interface Props {}
      type Mode = 'a' | 'b';
      export enum Color { Red }
      class Store {}
      """).orElseThrow();

    assertThat(facts.language()).isEqualTo("ts");
    assertThat(facts.unit()).isEqualTo("web/src/ui/App");
    assertThat(facts.imports()).containsExactly(
      "react", "web/src/db/client", "web/src/ui/styles.css", "web/src/ui/widgets/index", "web/src/legacy", "web/src/ui/Lazy");
    assertThat(facts.symbols()).extracting(Symbol::name, Symbol::kind).containsExactly(
      tuple("legacy", "variable"), tuple("lazy", "variable"),
      tuple("App", "function"), tuple("VERSION", "variable"), tuple("Props", "interface"),
      tuple("Mode", "type"), tuple("Color", "enum"), tuple("Store", "class"));
    assertThat(facts.symbols().get(2).line()).isEqualTo(7);
  }

  @Test
  void scriptSupportsJavaScriptButNotDeclarationFiles() {
    var indexer = new ScriptSourceIndexer();
    assertThat(indexer.supports("a.mjs")).isTrue();
    assertThat(indexer.supports("types.d.ts")).isFalse();
    assertThat(indexer.index("a.js", "export function f() {}").orElseThrow().language()).isEqualTo("js");
  }

  @Test
  void pythonModulesClassesAndRelativeImports() {
    var facts = new PythonSourceIndexer().index("src/shop/orders.py", """
      from .models import Order, Line as L
      from ..core import (config)
      import os, sys as system
      from shop import *

      @dataclass
      class OrderService:
          def place(self, order):
              return order

          async def cancel(self):
              pass

      def helper():
          pass
      """).orElseThrow();

    assertThat(facts.unit()).isEqualTo("shop.orders");
    assertThat(facts.imports()).containsExactly(
      "shop.models", "shop.models.Order", "shop.models.Line", "core", "core.config", "os", "sys", "shop");
    assertThat(facts.symbols()).extracting(Symbol::qualifiedName, Symbol::kind).containsExactly(
      tuple("shop.orders.OrderService", "class"),
      tuple("shop.orders.OrderService.place", "method"),
      tuple("shop.orders.OrderService.cancel", "method"),
      tuple("shop.orders.helper", "function"));
  }

  /** Inputs that made the earlier patterns backtrack for seconds to minutes (security review). */
  @Test
  void hostileInputsAreIndexedInLinearTime() {
    var spaces = " ".repeat(200_000);
    var newlines = "\n".repeat(200_000);
    var dots = ".".repeat(200_000);
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
      var script = new ScriptSourceIndexer();
      script.index("a.js", "import" + spaces);
      script.index("b.js", newlines + "x");
      script.index("c.js", "from" + spaces + "x\n" + "import" + "\t".repeat(3000) + "y");
      var python = new PythonSourceIndexer();
      python.index("a.py", "from " + dots);
      python.index("b.py", "from " + ".".repeat(3000) + " x");
      python.index("c.py", newlines);
    });
  }

  @Test
  void pythonPackageInitResolvesRelativeImportsFromThePackage() {
    var facts = new PythonSourceIndexer().index("shop/__init__.py", "from . import orders\n").orElseThrow();

    assertThat(facts.unit()).isEqualTo("shop");
    assertThat(facts.imports()).containsExactly("shop", "shop.orders");
  }
}
