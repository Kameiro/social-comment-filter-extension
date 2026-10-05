FROM node:22-bookworm-slim

WORKDIR /app
ENV NODE_ENV=production

COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

COPY server.mjs provision-user.mjs schema.sql ./
COPY admin ./admin

USER node
EXPOSE 8787
CMD ["node", "server.mjs"]
