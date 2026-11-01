# FoodBridge OpenAPI Contract & Mock Server

The agreed OpenAPI 3.0.3 contract between the frontend and backend tiers is located at:

`contracts/openapi.yaml`

## Validate the Contract

```bash
npx --yes @redocly/cli lint contracts/openapi.yaml
```

## Run the Mock Server (One Command)

Generate and run a live HTTP mock server directly from `contracts/openapi.yaml` on port `4010`:

```bash
npx --yes @stoplight/prism-cli mock contracts/openapi.yaml --port 4010
```

