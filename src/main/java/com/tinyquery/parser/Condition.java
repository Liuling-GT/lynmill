package com.tinyquery.parser;

public sealed interface Condition
        permits SimpleCondition, AndCondition, OrCondition, NotCondition {
}