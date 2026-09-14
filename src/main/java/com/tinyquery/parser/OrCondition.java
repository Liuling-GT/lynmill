package com.tinyquery.parser;

public record OrCondition(Condition left, Condition right) implements Condition {
}
