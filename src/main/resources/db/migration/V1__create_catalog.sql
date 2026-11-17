create table import_jobs (
    id uuid primary key,
    digest varchar(64) not null unique,
    file_name varchar(255) not null,
    status varchar(16) not null check (status in ('PENDING', 'COMPLETE')),
    records_read integer not null check (records_read > 0),
    records_processed integer not null check (records_processed between 0 and records_read),
    changed integer not null,
    unchanged integer not null,
    rejected integer not null,
    rejected_by_reason jsonb not null,
    started_at timestamptz not null,
    duration_ms bigint,
    check (records_processed = changed + unchanged + rejected)
);

create sequence catalog_revision;

create table products (
    article_number varchar(64) primary key,
    category text,
    lifecycle_status text,
    search_text text not null,
    product_data jsonb not null,
    revision bigint not null,
    updated_at timestamptz not null
);
create index products_category_status_idx on products (category, lifecycle_status, article_number);

create table product_changes (
    revision bigint primary key,
    article_number varchar(64) not null references products (article_number),
    import_id uuid not null references import_jobs (id),
    record_number integer not null,
    changed_at timestamptz not null,
    before_data jsonb,
    after_data jsonb not null
);
create index product_changes_history_idx on product_changes (article_number, revision desc);

create table dead_letters (
    id uuid primary key,
    import_id uuid not null references import_jobs (id),
    record_number integer not null,
    source jsonb not null,
    article_number varchar(64),
    initial_reason varchar(48) not null,
    reason varchar(48) not null,
    detail text not null,
    rejected_at timestamptz not null,
    rejected_revision bigint not null,
    retry_attempts integer not null,
    last_attempt_at timestamptz,
    resolved_at timestamptz,
    unique (import_id, record_number)
);
create index dead_letters_reason_idx on dead_letters (reason, resolved_at, rejected_at desc);

create table import_accepted_articles (
    import_id uuid not null references import_jobs (id),
    article_number varchar(64) not null,
    primary key (import_id, article_number)
);

create table catalog_write_lock (
    id int primary key check (id = 1)
);
insert into catalog_write_lock (id) values (1);
