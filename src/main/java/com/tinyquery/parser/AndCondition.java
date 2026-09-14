package com.tinyquery.parser;

public record AndCondition(Condition left, Condition right) implements Condition {
}