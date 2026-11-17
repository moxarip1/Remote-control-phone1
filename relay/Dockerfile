FROM node:20-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
EXPOSE 8787
ENV PORT=8787
ENV HOST=0.0.0.0
USER node
CMD ["node", "src/index.js"]
