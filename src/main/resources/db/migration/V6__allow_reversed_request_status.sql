alter table requests drop constraint if exists requests_status_check;

alter table requests add constraint requests_status_check
    check (status in ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'REVERSED'));

alter table handovers drop constraint if exists handovers_check;
alter table handovers add constraint handovers_check
    check (status <> 'COLLECTED' or completed_at is not null);

update requests r
set status = 'DECLINED',
    decided_at = coalesce(r.decided_at, clock_timestamp())
where r.status = 'PENDING'
  and exists (
      select 1
      from listings l
      where l.id = r.listing_id
        and l.status in ('RESERVED', 'COLLECTED', 'REVERSED')
  );

create table if not exists auth_tokens (
    token varchar(255) primary key,
    username varchar(255) not null,
    expires_at timestamptz not null,
    revoked boolean not null default false,
    created_at timestamptz not null default clock_timestamp()
);
