import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Layout from "@theme/Layout";
import type { ReactNode } from "react";

const areas: { title: string; text: string; to: string }[] = [
  { title: "Getting started", text: "Install, sign in, and get a first live value.", to: "/manual/getting-started/" },
  { title: "Gateway admin", text: "Connections, tags, historian, alarms, databases, and backups.", to: "/manual/gateway/connections/" },
  { title: "Control Canvas", text: "Draw screens, bind them to tags, and open them live.", to: "/manual/studio/open/" },
  { title: "PLC", text: "Write, check, and deploy Structured Text.", to: "/manual/plc/overview/" },
  { title: "Wishlist", text: "Features that are not built yet. Pick one and send a pull request.", to: "/manual/wishlist/" },
  { title: "Safety", text: "Beta software: test hardware, safe conditions only.", to: "/manual/safety/" },
];

export default function Home(): ReactNode {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description="How to run a plant with ERIC, from the screen.">
      <header className="oip-hero">
        <h1>ERIC</h1>
        <p>Engineered Runtime Interface &amp; Control. {siteConfig.tagline}. The manual shows the clicks. Scripts and the API stay available when you want them.</p>
        <p>
          <Link className="button button--primary button--lg" to="/manual/getting-started/">
            Start here
          </Link>{" "}
          <Link className="button button--secondary button--lg" href="https://github.com/neh773/eric-platform/blob/main/CONTRIBUTING.md">
            Contribute a feature
          </Link>
        </p>
      </header>
      <section className="oip-grid">
        {areas.map((area) => (
          <Link key={area.title} className="oip-card" to={area.to}>
            <strong>{area.title}</strong>
            <span>{area.text}</span>
          </Link>
        ))}
      </section>
    </Layout>
  );
}
