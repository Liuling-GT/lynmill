package com.tinyquery.parser.where;

public sealed interface Condition
        permits SimpleCondition, AndCondition, OrCondition, NotCondition {
}