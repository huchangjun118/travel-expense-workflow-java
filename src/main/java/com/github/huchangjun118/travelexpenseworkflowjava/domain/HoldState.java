package com.github.huchangjun118.travelexpenseworkflowjava.domain;

/** 预算占用生命周期：未占用 -> 已占用 -> 已释放 / 已核销。 */
public enum HoldState {
    NONE, HELD, RELEASED, CONSUMED
}
