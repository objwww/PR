-- The operator API writes the existing runtime switch; evaluations can read its provenance.
do $$ begin
    if exists (select from pg_roles where rolname='control_app') then
        grant select,insert,update on alert_runtime_flag to control_app;
    end if;
    if exists (select from pg_roles where rolname='eval_app') then
        grant select on alert_runtime_flag to eval_app;
    end if;
end $$;
revoke all on alert_runtime_flag from public;
