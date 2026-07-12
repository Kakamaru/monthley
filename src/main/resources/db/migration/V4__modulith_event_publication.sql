CREATE TABLE event_publication (
  id                     BINARY(16)    NOT NULL,
  listener_id            VARCHAR(512)  NOT NULL,
  event_type             VARCHAR(512)  NOT NULL,
  serialized_event       VARCHAR(4000) NOT NULL,
  publication_date       TIMESTAMP(6)  NOT NULL,
  completion_date        TIMESTAMP(6)  NULL,
  last_resubmission_date TIMESTAMP(6)  NULL,
  completion_attempts    INT           NOT NULL DEFAULT 0,
  status                 VARCHAR(20)   NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_event_pub_completion ON event_publication (completion_date);
CREATE INDEX idx_event_pub_status ON event_publication (status, publication_date);
