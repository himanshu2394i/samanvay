-- Postgres 15+ revokes CREATE on public from PUBLIC. samanvay_app needs
-- CREATE so Modulith can initialize event_publication (application.yml
-- spring.modulith.events.jdbc-schema-initialization). Not an audit grant.
GRANT CREATE ON SCHEMA public TO samanvay_app;
