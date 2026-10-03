package in.samanvay.departments.education;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Education's own database connection, only when {@code education.db.url} is set. The account named here should be able to read
 * (and for a real department, write) Education's tables, nothing else.
 *
 * <p>ponytail: one new connection per query (no pool); fine for demo traffic, add a pool before real load.
 */
@Configuration
@ConditionalOnExpression("'${education.db.url:}' != ''")
class DatabaseConfig {

    @Bean
    DataSource educationDataSource(@Value("${education.db.url}") String url, @Value("${education.db.user:}") String user,
            @Value("${education.db.password:}") String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    @Bean
    JdbcClient educationJdbc(DataSource educationDataSource) {
        return JdbcClient.create(educationDataSource);
    }
}
