package org.communityvortex.mcp.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A small polyglot workspace used by the index tests. */
final class SampleWorkspace {

  private SampleWorkspace() {
  }

  static void write(Path root, String relative, String content) throws IOException {
    var file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  static Path create(Path root) throws IOException {
    write(root, "src/main/java/com/acme/domain/Order.java", """
      package com.acme.domain;

      import java.util.List;

      public class Order {
        public static final int MAX_LINES = 10;
        private final List<String> lines;

        public Order(List<String> lines) {
          this.lines = lines;
        }

        public int total() {
          return lines.size();
        }

        public static class Line {
        }
      }
      """);
    write(root, "src/main/java/com/acme/infra/OrderRepository.java", """
      package com.acme.infra;

      import com.acme.domain.Order;
      import java.util.List;

      public interface OrderRepository {
        Order load(String id);
      }
      """);
    write(root, "src/main/java/com/acme/app/Checkout.java", """
      package com.acme.app;

      import com.acme.domain.Order;
      import com.acme.infra.OrderRepository;
      import java.util.List;

      public class Checkout {
        private final OrderRepository repository;

        Checkout(OrderRepository repository) {
          this.repository = repository;
        }

        int run() {
          Order order = new Order(List.of("a"));
          int limit = Order.MAX_LINES;
          return order.total() + repository.load("1").total() + limit;
        }

        int total() {
          return 0;
        }
      }
      """);
    write(root, "web/src/db/client.ts", """
      export class DbClient {}
      export function connect(url: string) { return new DbClient(); }
      """);
    write(root, "web/src/ui/App.tsx", """
      import React from 'react';
      import { connect } from '../db/client';
      const lazy = import('./Lazy');
      export default function App() { return connect('x'); }
      """);
    write(root, "py/shop/orders.py", """
      from .models import Order
      import os, sys
      from shop import pricing as p

      class OrderService:
          def place(self, order):
              return order

      def helper():
          return OrderService()
      """);
    write(root, "node_modules/lib/index.js", "export function ignored() {}");
    write(root, "README.md", "connect OrderService");
    return root;
  }
}
