package in.samanvay.departments.revenue;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Revenue's own database connection, only when {@code revenue.db.url} is set. The account named here should be able to read
 * (and for a real department, write) Revenue's tables, nothing else.
 *
 * <p>ponytail: one new connection per query (no pool); fine for demo traffic, add a pool before real load.
 */
@Configuration
@ConditionalOnExpression("'${revenue.db.url:}' != ''")
class DatabaseConfig {

    @Bean
    DataSource revenueDataSource(@Value("${revenue.db.url}") String url, @Value("${revenue.db.user:}") String user,
            @Value("${revenue.db.password:}") String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    @Bean
    JdbcClient revenueJdbc(DataSource revenueDataSource) {
        return JdbcClient.create(revenueDataSource);
    }
}
