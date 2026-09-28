package com.tinyquery.parser.where;

public record AndCondition(Condition left, Condition right) implements Condition {
}