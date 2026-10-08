-- THE DIRECT AGENT PATH IS RETIRED (qits-767): a project's agent container is a front desk on a
-- runner now (V37), authenticated with its own qits_tok_ held on front_desk, so the per-container idp
-- client pair this table held (V3) has no reader left. The legacy one-shot (deskhost/LegacyAgentPlaces)
-- reaps the agent-container clients by listing qits-idp, not by reading this table, so dropping it in
-- the same release is safe.
drop table agent_credential;
