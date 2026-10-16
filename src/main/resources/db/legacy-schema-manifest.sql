-- Inventario estricto de objetos de negocio en public (PostgreSQL 15).
-- La tabla de historial Flyway es la unica exclusion deliberada.
WITH objects(line) AS (
    SELECT 'DB|' || pg_encoding_to_char(d.encoding) || '|' || d.datcollate || '|' || d.datctype
    FROM pg_database d WHERE d.datname = current_database()
    UNION ALL
    SELECT 'EXT|' || e.extname || '|' || n.nspname
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname <> 'plpgsql'
    UNION ALL
    SELECT 'TYPE|' || t.typname || '|' || t.typtype::text || '|' || format_type(t.oid, NULL)
    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'public' AND t.typtype IN ('e','d','c','r','m')
      AND t.typname <> 'flyway_schema_history'
    UNION ALL
    SELECT 'REL|' || c.relname || '|' || c.relkind::text
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','S','f')
      AND c.relname <> 'flyway_schema_history'
    UNION ALL
    SELECT 'COL|' || c.relname || '|' || a.attnum || '|' || a.attname || '|' ||
           format_type(a.atttypid, a.atttypmod) || '|' || a.attnotnull || '|' ||
           coalesce(pg_get_expr(d.adbin, d.adrelid), '') || '|' ||
           a.attidentity::text || '|' || a.attgenerated::text
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid
      LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
    WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','f')
      AND c.relname <> 'flyway_schema_history'
      AND a.attnum > 0 AND NOT a.attisdropped
    UNION ALL
    SELECT 'CON|' || c.relname || '|' || k.conname || '|' || k.contype::text || '|' ||
           k.convalidated || '|' || pg_get_constraintdef(k.oid, true)
    FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname <> 'flyway_schema_history'
    UNION ALL
    SELECT 'IDX|' || c.relname || '|' || i.relname || '|' || pg_get_indexdef(i.oid)
    FROM pg_index x JOIN pg_class c ON c.oid = x.indrelid
      JOIN pg_class i ON i.oid = x.indexrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname <> 'flyway_schema_history'
    UNION ALL
    SELECT 'SEQ|' || c.relname || '|' || format_type(s.seqtypid, NULL) || '|' ||
           s.seqstart || '|' || s.seqincrement || '|' || s.seqmin || '|' ||
           s.seqmax || '|' || s.seqcache || '|' || s.seqcycle || '|' ||
           coalesce(t.relname || '.' || a.attname, '')
    FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      LEFT JOIN pg_depend dep ON dep.objid = c.oid AND dep.classid = 'pg_class'::regclass
        AND dep.refclassid = 'pg_class'::regclass AND dep.deptype IN ('a','i')
      LEFT JOIN pg_class t ON t.oid = dep.refobjid
      LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = dep.refobjsubid
    WHERE n.nspname = 'public'
    UNION ALL
    SELECT 'TRG|' || c.relname || '|' || pg_get_triggerdef(t.oid)
    FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND NOT t.tgisinternal
    UNION ALL
    SELECT 'FUN|' || p.proname || '|' || pg_get_function_identity_arguments(p.oid) ||
           '|' || pg_get_functiondef(p.oid)
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
)
SELECT line FROM objects ORDER BY line;
