## ⚡ CSV Query Performance Testing(1,000,000 rows)

Test Environment：JDK 17，32GB Memory，SSD

| Query                                                      | Elapsed Time (Average of 5 runs) |
|------------------------------------------------------------|----------------------------------|
| `select count(*) from huge.csv`                            | 14.00 ms                         |
| `select * from huge.csv where age > 50`                    | 1950.00 ms                       |
| `select name from huge.csv where city = '北京'`              | 440.33 ms                        |
| `select sum(salary) from huge.csv where city = '上海'`       | 12.67 ms                         |
| `select avg(age) from huge.csv`                            | 12.33 ms                         |
| `select count(*), avg(salary), max(age) where city = '深圳'` | 14.00 ms                         |

> **Columnar Storage + Vectorized Execution**<br>
> Effortlessly query millions of rows with SQL. No database required.
> > **Note**: CSV is suitable for small-to-medium datasets (up to millions of rows).<br>
> For datasets at TB scale, it is recommended to use columnar file formats
> such as Parquet or ORC, or to adopt distributed engines like HDFS or ClickHouse.<br>
> TinyQuery's columnar storage design follows the same principle as these solutions.