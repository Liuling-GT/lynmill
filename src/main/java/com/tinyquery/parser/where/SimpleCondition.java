package com.tinyquery.parser.where;

public record SimpleCondition(String field, String op, String value) implements Condition {
}
