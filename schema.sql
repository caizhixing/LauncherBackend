-- 用户反馈表（PRD 第 5 节，首次部署手工执行）
CREATE TABLE `feedback` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(50)   NOT NULL,
  `content`      VARCHAR(1000) NOT NULL,
  `category`     VARCHAR(20)   NOT NULL,
  `app_name`     VARCHAR(50)   NOT NULL,
  `app_version`  VARCHAR(20)   NOT NULL,
  `device_model` VARCHAR(100)  DEFAULT NULL,
  `os_version`   VARCHAR(20)   DEFAULT NULL,
  `device_id`    VARCHAR(64)   NOT NULL,
  `contact`      VARCHAR(100)  DEFAULT NULL,
  `status`       TINYINT       NOT NULL DEFAULT 0,
  `images`       JSON          DEFAULT NULL,
  `created_at`   DATETIME      NOT NULL,
  `updated_at`   DATETIME      NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_filter` (`app_name`, `category`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
