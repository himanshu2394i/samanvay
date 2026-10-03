package in.samanvay.departments.dbt;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * DBT's own database connection, only when {@code dbt.db.url} is set. The account named here should be able to read
 * (and for a real department, write) DBT's tables, nothing else.
 *
 * <p>ponytail: one new connection per query (no pool); fine for demo traffic, add a pool before real load.
 */
@Configuration
@ConditionalOnExpression("'${dbt.db.url:}' != ''")
class DatabaseConfig {

    @Bean
    DataSource dbtDataSource(@Value("${dbt.db.url}") String url, @Value("${dbt.db.user:}") String user,
            @Value("${dbt.db.password:}") String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    @Bean
    JdbcClient dbtJdbc(DataSource dbtDataSource) {
        return JdbcClient.create(dbtDataSource);
    }
}
