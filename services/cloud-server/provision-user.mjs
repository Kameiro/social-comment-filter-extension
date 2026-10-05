import process from "node:process";
import bcrypt from "bcryptjs";
import pg from "pg";

const { Pool } = pg;
const args = process.argv.slice(2);
const email = args[0];
const passwordArg = args[1];
const tenantName = args.slice(2).join(" ") || email;
let password = passwordArg;
if (passwordArg === "--password-stdin") {
  let input = "";
  for await (const chunk of process.stdin) input += chunk;
  password = input.replace(/\r?\n$/, "");
}
if (!email || !password) throw new Error("用法：provision-user.mjs user@example.com --password-stdin TenantName");
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const client = await pool.connect();
try {
  await client.query("begin");
  const tenant = await client.query("insert into tenants (name) values ($1) returning id", [tenantName]);
  const hash = await bcrypt.hash(password, 12);
  await client.query("insert into accounts (tenant_id,email,password_hash) values ($1,$2,$3)", [tenant.rows[0].id, email.toLowerCase(), hash]);
  await client.query("commit");
  console.log(`created ${email} in tenant ${tenant.rows[0].id}`);
} catch (error) {
  await client.query("rollback");
  throw error;
} finally {
  client.release();
  await pool.end();
}
