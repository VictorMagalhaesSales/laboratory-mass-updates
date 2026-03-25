INSERT INTO documento_carga (numero_documento, status, peso, valor, volume, regiao, versao)
SELECT
    'DOC-' || lpad(gs::text, 10, '0'),
    'DISPONIVEL',
    round((random() * 999 + 1)::numeric, 2),
    round((random() * 49999 + 1)::numeric, 2),
    round((random() * 99 + 1)::numeric, 2),
    'SUL',
    1
FROM generate_series(1, 1000) AS gs;

INSERT INTO planejamento (usuario_id, status) VALUES (1, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (2, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (3, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (4, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (5, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (6, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (7, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (8, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (9, 'DRAFT');
INSERT INTO planejamento (usuario_id, status) VALUES (10, 'DRAFT');
