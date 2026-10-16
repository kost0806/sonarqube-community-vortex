package com.acme.domain;

import com.acme.infra.Db;

public class Order {

  private int unused;

  public int total() {
    System.out.println("computing total");
    return new Db().count();
  }
}
